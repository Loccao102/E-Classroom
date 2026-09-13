package com.eclassroom.core.communication;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CommunicationService {
    private static final int MAX_TITLE = 255;
    private static final int MAX_ANNOUNCEMENT_BODY = 20_000;
    private static final int MAX_COMMENT_BODY = 5_000;
    private static final int MAX_MESSAGE_BODY = 5_000;

    private static final Set<String> COMMENT_VISIBILITY = Set.of(
            "STAFF_ONLY", "GUARDIAN", "STUDENT_AND_GUARDIAN");

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final NotificationService notifications;

    public CommunicationService(JdbcTemplate jdbc, AccessService access, NotificationService notifications) {
        this.jdbc = jdbc;
        this.access = access;
        this.notifications = notifications;
    }

    @Transactional
    public UUID announce(UUID schoolId, String title, String body, String targetType, UUID targetId, UUID actor) {
        return announce(schoolId, title, body, targetType, targetId, null, false, actor);
    }

    @Transactional
    public UUID announce(UUID schoolId, String title, String body, String targetType, UUID targetId,
                         OffsetDateTime expiresAt, boolean pinned, UUID actor) {
        String safeTitle = requireText(title, MAX_TITLE, "INVALID_ANNOUNCEMENT_TITLE", "Announcement title is required");
        String safeBody = requireText(body, MAX_ANNOUNCEMENT_BODY, "INVALID_ANNOUNCEMENT_BODY", "Announcement body is required");
        String safeTarget = targetType == null ? "" : targetType.trim().toUpperCase();

        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw ApiException.badRequest("INVALID_ANNOUNCEMENT_EXPIRY", "Announcement expiry must be in the future");
        }

        if ("SCHOOL".equals(safeTarget)) {
            access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN");
            targetId = null;
        } else if ("CLASSROOM".equals(safeTarget)) {
            if (targetId == null) {
                throw ApiException.badRequest("TARGET_REQUIRED", "Classroom target is required");
            }
            Integer classroom = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM academic.classrooms WHERE id=? AND school_id=? AND status='ACTIVE'",
                    Integer.class, targetId, schoolId);
            if (classroom == null || classroom == 0) {
                throw ApiException.badRequest("INVALID_TARGET", "Classroom target is not active in this school");
            }
            access.requireClassTeacherOrAdmin(schoolId, actor, targetId);
        } else {
            throw ApiException.badRequest("INVALID_TARGET", "Target must be SCHOOL or CLASSROOM");
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO communication.announcements" +
                        "(id,school_id,title,body,target_type,target_id,published_by,expires_at,pinned) VALUES (?,?,?,?,?,?,?,?,?)",
                id, schoolId, safeTitle, safeBody, safeTarget, targetId, actor, expiresAt, pinned);
        List<UUID> recipients = recipients(schoolId, safeTarget, targetId);
        notifications.notifyUsers(
                schoolId, recipients, "announcement.published", safeTitle, safeBody, "ANNOUNCEMENT", id,
                Map.of("targetType", safeTarget, "pinned", pinned));
        return id;
    }

    public List<Map<String, Object>> announcements(UUID schoolId, UUID userId) {
        access.requireMembership(schoolId, userId);
        return jdbc.queryForList(
                "SELECT a.* FROM communication.announcements a WHERE a.school_id=? " +
                        "AND a.published_at<=NOW() AND (a.expires_at IS NULL OR a.expires_at>NOW()) " +
                        "AND (a.target_type='SCHOOL' OR (a.target_type='CLASSROOM' AND a.target_id IN (" +
                        "SELECT e.classroom_id FROM academic.class_enrollments e JOIN academic.students s ON s.id=e.student_id " +
                        "WHERE e.school_id=? AND e.status='ACTIVE' AND s.user_id=? " +
                        "UNION SELECT e.classroom_id FROM academic.class_enrollments e " +
                        "JOIN academic.student_guardians sg ON sg.student_id=e.student_id " +
                        "JOIN academic.guardians g ON g.id=sg.guardian_id " +
                        "WHERE e.school_id=? AND e.status='ACTIVE' AND g.user_id=? " +
                        "UNION SELECT ta.classroom_id FROM academic.teaching_assignments ta " +
                        "JOIN academic.teachers t ON t.id=ta.teacher_id " +
                        "WHERE ta.school_id=? AND ta.status='ACTIVE' AND t.user_id=?))) " +
                        "ORDER BY a.pinned DESC,a.published_at DESC,a.id DESC",
                schoolId, schoolId, userId, schoolId, userId, schoolId, userId);
    }

    @Transactional
    public UUID comment(UUID schoolId, UUID studentId, String body, String visibility, UUID actor) {
        access.requireTeacherOfStudentOrAdmin(schoolId, actor, studentId);
        String safeBody = requireText(body, MAX_COMMENT_BODY, "INVALID_COMMENT_BODY", "Comment body is required");
        String safeVisibility = normalizeVisibility(visibility);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO communication.teacher_comments(id,school_id,student_id,teacher_user_id,body,visibility) VALUES (?,?,?,?,?,?)",
                id, schoolId, studentId, actor, safeBody, safeVisibility);

        List<UUID> recipients = new ArrayList<>();
        if ("GUARDIAN".equals(safeVisibility) || "STUDENT_AND_GUARDIAN".equals(safeVisibility)) {
            recipients.addAll(notifications.guardianRecipients(schoolId, studentId));
        }
        if ("STUDENT_AND_GUARDIAN".equals(safeVisibility)) {
            List<UUID> students = jdbc.query(
                    "SELECT user_id FROM academic.students WHERE id=? AND school_id=? AND user_id IS NOT NULL AND status='ACTIVE'",
                    (rs, i) -> UUID.fromString(rs.getString(1)), studentId, schoolId);
            recipients.addAll(students);
        }
        notifications.notifyUsers(
                schoolId,
                recipients,
                "teacher-comment.created",
                "New teacher comment",
                safeBody,
                "TEACHER_COMMENT",
                id,
                Map.of("studentId", studentId, "visibility", safeVisibility));
        return id;
    }

    public List<Map<String, Object>> comments(UUID schoolId, UUID studentId, UUID actor) {
        access.requireStudentView(schoolId, actor, studentId);
        if (access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN") || access.hasRole(schoolId, actor, "TEACHER")) {
            return jdbc.queryForList(
                    "SELECT c.*,u.full_name teacher_name FROM communication.teacher_comments c " +
                            "JOIN identity.users u ON u.id=c.teacher_user_id " +
                            "WHERE c.school_id=? AND c.student_id=? ORDER BY c.created_at DESC,c.id DESC",
                    schoolId, studentId);
        }
        if (access.hasRole(schoolId, actor, "PARENT")) {
            return jdbc.queryForList(
                    "SELECT c.*,u.full_name teacher_name FROM communication.teacher_comments c " +
                            "JOIN identity.users u ON u.id=c.teacher_user_id WHERE c.school_id=? AND c.student_id=? " +
                            "AND c.visibility IN ('GUARDIAN','STUDENT_AND_GUARDIAN') ORDER BY c.created_at DESC,c.id DESC",
                    schoolId, studentId);
        }
        return jdbc.queryForList(
                "SELECT c.*,u.full_name teacher_name FROM communication.teacher_comments c " +
                        "JOIN identity.users u ON u.id=c.teacher_user_id WHERE c.school_id=? AND c.student_id=? " +
                        "AND c.visibility='STUDENT_AND_GUARDIAN' ORDER BY c.created_at DESC,c.id DESC",
                schoolId, studentId);
    }

    @Transactional
    public UUID conversation(UUID schoolId, String subject, List<UUID> participantIds, UUID actor) {
        access.requireMembership(schoolId, actor);
        String safeSubject = subject == null || subject.isBlank()
                ? null
                : requireText(subject, MAX_TITLE, "INVALID_CONVERSATION_SUBJECT", "Conversation subject is too long");

        LinkedHashSet<UUID> all = new LinkedHashSet<>(participantIds == null ? List.of() : participantIds);
        all.add(actor);
        if (all.size() < 2) {
            throw ApiException.badRequest("PARTICIPANT_REQUIRED", "A conversation requires at least one other participant");
        }
        if (all.size() > 20) {
            throw ApiException.badRequest("TOO_MANY_PARTICIPANTS", "A conversation may contain at most 20 participants");
        }
        for (UUID user : all) {
            if (!user.equals(actor)) {
                requireCommunicationAllowed(schoolId, actor, user);
            }
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO communication.conversations(id,school_id,subject,created_by) VALUES (?,?,?,?)",
                id, schoolId, safeSubject, actor);
        List<Object[]> rows = all.stream().map(user -> new Object[]{id, user}).toList();
        jdbc.batchUpdate(
                "INSERT INTO communication.conversation_participants(conversation_id,user_id) VALUES (?,?)",
                rows);
        return id;
    }

    public List<Map<String, Object>> conversations(UUID schoolId, UUID userId) {
        access.requireMembership(schoolId, userId);
        return jdbc.queryForList(
                "WITH latest AS (" +
                        "SELECT DISTINCT ON (conversation_id) conversation_id,body,created_at FROM communication.messages " +
                        "ORDER BY conversation_id,created_at DESC,id DESC), " +
                        "unread AS (SELECT p.conversation_id,COUNT(m.id) unread_count FROM communication.conversation_participants p " +
                        "JOIN communication.messages m ON m.conversation_id=p.conversation_id AND m.sender_user_id<>p.user_id " +
                        "AND (p.last_read_at IS NULL OR m.created_at>p.last_read_at) WHERE p.user_id=? GROUP BY p.conversation_id) " +
                        "SELECT c.id,c.subject,c.created_at,c.last_message_at,l.body last_message,l.created_at last_message_at_detail, " +
                        "COALESCE(u.unread_count,0) unread_count,p.muted FROM communication.conversations c " +
                        "JOIN communication.conversation_participants p ON p.conversation_id=c.id AND p.user_id=? " +
                        "LEFT JOIN latest l ON l.conversation_id=c.id LEFT JOIN unread u ON u.conversation_id=c.id " +
                        "WHERE c.school_id=? ORDER BY COALESCE(c.last_message_at,c.created_at) DESC,c.id DESC",
                userId, userId, schoolId);
    }

    /** Backward-compatible message list; bounded to the latest 100 messages. */
    public List<Map<String, Object>> messages(UUID conversationId, UUID userId) {
        MessagePage page = messagePage(conversationId, userId, 100, null, null);
        List<Map<String, Object>> chronological = new ArrayList<>(page.items());
        Collections.reverse(chronological);
        markConversationRead(conversationId, userId);
        return chronological;
    }

    public MessagePage messagePage(UUID conversationId, UUID userId, int limit,
                                   OffsetDateTime beforeCreatedAt, UUID beforeId) {
        requireParticipant(conversationId, userId);
        int bounded = Math.max(1, Math.min(limit, 100));
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw ApiException.badRequest("INVALID_CURSOR", "Both beforeCreatedAt and beforeId are required");
        }
        StringBuilder sql = new StringBuilder(
                "SELECT m.id,m.sender_user_id,u.full_name sender_name,m.body,m.created_at " +
                        "FROM communication.messages m JOIN identity.users u ON u.id=m.sender_user_id " +
                        "WHERE m.conversation_id=?");
        List<Object> args = new ArrayList<>();
        args.add(conversationId);
        if (beforeCreatedAt != null) {
            sql.append(" AND (m.created_at,m.id)<(?,?)");
            args.add(beforeCreatedAt);
            args.add(beforeId);
        }
        sql.append(" ORDER BY m.created_at DESC,m.id DESC LIMIT ?");
        args.add(bounded + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean hasMore = rows.size() > bounded;
        List<Map<String, Object>> items = hasMore ? List.copyOf(rows.subList(0, bounded)) : List.copyOf(rows);
        MessageCursor next = null;
        if (hasMore && !items.isEmpty()) {
            Map<String, Object> last = items.getLast();
            next = new MessageCursor(toOffsetDateTime(last.get("created_at")), (UUID) last.get("id"));
        }
        return new MessagePage(items, next);
    }

    @Transactional
    public UUID send(UUID conversationId, String body, UUID userId) {
        Map<String, Object> conversation = requireParticipant(conversationId, userId);
        String safeBody = requireText(body, MAX_MESSAGE_BODY, "INVALID_MESSAGE_BODY", "Message body is required");
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO communication.messages(id,conversation_id,sender_user_id,body) VALUES (?,?,?,?)",
                id, conversationId, userId, safeBody);
        jdbc.update(
                "UPDATE communication.conversations SET last_message_at=NOW() WHERE id=?",
                conversationId);
        jdbc.update(
                "UPDATE communication.conversation_participants SET last_read_at=NOW() WHERE conversation_id=? AND user_id=?",
                conversationId, userId);

        List<UUID> recipients = jdbc.query(
                "SELECT user_id FROM communication.conversation_participants WHERE conversation_id=? AND user_id<>? AND muted=FALSE",
                (rs, i) -> UUID.fromString(rs.getString(1)), conversationId, userId);
        notifications.notifyUsers(
                (UUID) conversation.get("school_id"), recipients, "message.created", "New message", safeBody,
                "CONVERSATION", conversationId, Map.of("messageId", id, "senderUserId", userId));
        return id;
    }

    public void markConversationRead(UUID conversationId, UUID userId) {
        requireParticipant(conversationId, userId);
        jdbc.update(
                "UPDATE communication.conversation_participants SET last_read_at=NOW() WHERE conversation_id=? AND user_id=?",
                conversationId, userId);
    }

    public void setMuted(UUID conversationId, UUID userId, boolean muted) {
        requireParticipant(conversationId, userId);
        jdbc.update(
                "UPDATE communication.conversation_participants SET muted=? WHERE conversation_id=? AND user_id=?",
                muted, conversationId, userId);
    }

    private Map<String, Object> requireParticipant(UUID conversationId, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.* FROM communication.conversations c " +
                        "JOIN communication.conversation_participants p ON p.conversation_id=c.id " +
                        "WHERE c.id=? AND p.user_id=?",
                conversationId, userId);
        if (rows.isEmpty()) {
            throw ApiException.forbidden("Not a conversation participant");
        }
        return rows.getFirst();
    }

    private void requireCommunicationAllowed(UUID schoolId, UUID actor, UUID target) {
        Integer member = jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity.school_memberships WHERE school_id=? AND user_id=? AND status='ACTIVE'",
                Integer.class, schoolId, target);
        if (member == null || member == 0) {
            throw ApiException.badRequest("INVALID_PARTICIPANT", "Conversation participant is not an active school member");
        }
        if (access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN") || access.hasRole(schoolId, target, "SCHOOL_ADMIN")) {
            return;
        }

        if (access.hasRole(schoolId, actor, "PARENT") && isTeacherForGuardianChild(schoolId, actor, target)) {
            return;
        }
        if (access.hasRole(schoolId, actor, "TEACHER") && isGuardianOrStudentForTeacher(schoolId, actor, target)) {
            return;
        }
        if (access.hasRole(schoolId, actor, "STUDENT") && isTeacherForStudent(schoolId, actor, target)) {
            return;
        }
        throw ApiException.forbidden("Communication relationship does not authorize this participant");
    }

    private boolean isTeacherForGuardianChild(UUID schoolId, UUID guardianUserId, UUID teacherUserId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic.guardians g " +
                        "JOIN academic.student_guardians sg ON sg.guardian_id=g.id " +
                        "JOIN academic.class_enrollments e ON e.student_id=sg.student_id AND e.status='ACTIVE' " +
                        "JOIN academic.classrooms c ON c.id=e.classroom_id " +
                        "LEFT JOIN academic.teachers ht ON ht.id=c.homeroom_teacher_id " +
                        "LEFT JOIN academic.teaching_assignments ta ON ta.classroom_id=c.id AND ta.status='ACTIVE' " +
                        "LEFT JOIN academic.teachers at ON at.id=ta.teacher_id " +
                        "WHERE g.school_id=? AND g.user_id=? AND (ht.user_id=? OR at.user_id=?)",
                Integer.class, schoolId, guardianUserId, teacherUserId, teacherUserId);
        return count != null && count > 0;
    }

    private boolean isGuardianOrStudentForTeacher(UUID schoolId, UUID teacherUserId, UUID targetUserId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic.teachers t " +
                        "JOIN academic.teaching_assignments ta ON ta.teacher_id=t.id AND ta.status='ACTIVE' " +
                        "JOIN academic.class_enrollments e ON e.classroom_id=ta.classroom_id AND e.status='ACTIVE' " +
                        "JOIN academic.students s ON s.id=e.student_id " +
                        "LEFT JOIN academic.student_guardians sg ON sg.student_id=s.id " +
                        "LEFT JOIN academic.guardians g ON g.id=sg.guardian_id " +
                        "WHERE t.school_id=? AND t.user_id=? AND (s.user_id=? OR g.user_id=?)",
                Integer.class, schoolId, teacherUserId, targetUserId, targetUserId);
        if (count != null && count > 0) return true;

        Integer homeroom = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic.teachers t JOIN academic.classrooms c ON c.homeroom_teacher_id=t.id " +
                        "JOIN academic.class_enrollments e ON e.classroom_id=c.id AND e.status='ACTIVE' " +
                        "JOIN academic.students s ON s.id=e.student_id " +
                        "LEFT JOIN academic.student_guardians sg ON sg.student_id=s.id " +
                        "LEFT JOIN academic.guardians g ON g.id=sg.guardian_id " +
                        "WHERE t.school_id=? AND t.user_id=? AND (s.user_id=? OR g.user_id=?)",
                Integer.class, schoolId, teacherUserId, targetUserId, targetUserId);
        return homeroom != null && homeroom > 0;
    }

    private boolean isTeacherForStudent(UUID schoolId, UUID studentUserId, UUID teacherUserId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic.students s " +
                        "JOIN academic.class_enrollments e ON e.student_id=s.id AND e.status='ACTIVE' " +
                        "JOIN academic.classrooms c ON c.id=e.classroom_id " +
                        "LEFT JOIN academic.teachers ht ON ht.id=c.homeroom_teacher_id " +
                        "LEFT JOIN academic.teaching_assignments ta ON ta.classroom_id=c.id AND ta.status='ACTIVE' " +
                        "LEFT JOIN academic.teachers at ON at.id=ta.teacher_id " +
                        "WHERE s.school_id=? AND s.user_id=? AND (ht.user_id=? OR at.user_id=?)",
                Integer.class, schoolId, studentUserId, teacherUserId, teacherUserId);
        return count != null && count > 0;
    }

    private List<UUID> recipients(UUID schoolId, String type, UUID targetId) {
        if ("SCHOOL".equals(type)) {
            return jdbc.query(
                    "SELECT DISTINCT user_id FROM identity.school_memberships WHERE school_id=? AND status='ACTIVE'",
                    (rs, i) -> UUID.fromString(rs.getString(1)), schoolId);
        }
        return jdbc.query(
                "SELECT DISTINCT user_id FROM (" +
                        "SELECT s.user_id FROM academic.class_enrollments e JOIN academic.students s ON s.id=e.student_id " +
                        "WHERE e.school_id=? AND e.classroom_id=? AND e.status='ACTIVE' AND s.user_id IS NOT NULL " +
                        "UNION SELECT g.user_id FROM academic.class_enrollments e " +
                        "JOIN academic.student_guardians sg ON sg.student_id=e.student_id " +
                        "JOIN academic.guardians g ON g.id=sg.guardian_id " +
                        "WHERE e.school_id=? AND e.classroom_id=? AND e.status='ACTIVE' AND g.user_id IS NOT NULL " +
                        "UNION SELECT t.user_id FROM academic.teaching_assignments ta JOIN academic.teachers t ON t.id=ta.teacher_id " +
                        "WHERE ta.school_id=? AND ta.classroom_id=? AND ta.status='ACTIVE' AND t.user_id IS NOT NULL) x",
                (rs, i) -> UUID.fromString(rs.getString(1)),
                schoolId, targetId, schoolId, targetId, schoolId, targetId);
    }

    private String normalizeVisibility(String visibility) {
        String normalized = visibility == null || visibility.isBlank()
                ? "STUDENT_AND_GUARDIAN"
                : visibility.trim().toUpperCase();
        if ("GUARDIAN_AND_STUDENT".equals(normalized)) normalized = "STUDENT_AND_GUARDIAN";
        if (!COMMENT_VISIBILITY.contains(normalized)) {
            throw ApiException.badRequest("INVALID_COMMENT_VISIBILITY", "Unsupported teacher comment visibility");
        }
        return normalized;
    }

    private String requireText(String value, int max, String code, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(code, message);
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw ApiException.badRequest(code, message + " (max " + max + " characters)");
        }
        return normalized;
    }

    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(String.valueOf(value));
    }

    public record MessageCursor(OffsetDateTime beforeCreatedAt, UUID beforeId) {}
    public record MessagePage(List<Map<String, Object>> items, MessageCursor nextCursor) {}
}
