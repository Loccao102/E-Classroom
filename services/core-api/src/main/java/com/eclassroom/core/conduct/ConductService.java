package com.eclassroom.core.conduct;

import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.ApiException;
import com.eclassroom.core.shared.api.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ConductService {
    private static final Set<String> CATEGORIES = Set.of(
            "POSITIVE_RECOGNITION", "REMINDER", "VIOLATION", "ACHIEVEMENT", "GENERAL");
    private static final Set<String> SEVERITIES = Set.of("INFO", "LOW", "MEDIUM", "HIGH");
    private static final Set<String> VISIBILITIES = Set.of(
            "STAFF_ONLY", "GUARDIAN", "STUDENT", "STUDENT_AND_GUARDIAN");

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JsonMapper json;

    public ConductService(JdbcTemplate jdbc,
                          AccessService access,
                          NotificationService notifications,
                          AuditService audit,
                          JsonMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public ConductView create(UUID schoolId, UUID studentId, Command command, UUID actor) {
        requireStudent(schoolId, studentId);
        access.requireTeacherOfStudentOrAdmin(schoolId, actor, studentId);
        Values values = validate(schoolId, studentId, command, actor);

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conduct.records" +
                        "(id,school_id,student_id,category,severity,title,body,visibility,classroom_id,subject_id,occurred_at,recorded_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, schoolId, studentId, values.category(), values.severity(), values.title(), values.body(),
                values.visibility(), values.classroomId(), values.subjectId(), values.occurredAt(), actor);

        ConductView created = requireRecord(schoolId, studentId, id, false);
        audit.append(schoolId, actor, "CREATE", "CONDUCT_RECORD", id, null, snapshot(created), null);
        notifyAudience(schoolId, created, "student.conduct.created", actor);
        return created;
    }

    @Transactional
    public ConductView update(UUID schoolId, UUID studentId, UUID id, long expectedVersion,
                              String reason, Command command, UUID actor) {
        requireStudent(schoolId, studentId);
        access.requireTeacherOfStudentOrAdmin(schoolId, actor, studentId);
        String safeReason = requireText(reason, 1000, "REVISION_REASON_REQUIRED", "A revision reason is required");
        ConductView current = requireRecord(schoolId, studentId, id, true);
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "Conduct record was changed by another user");
        }
        Values values = validate(schoolId, studentId, command, actor);
        if (same(current, values)) return current;

        int updated = jdbc.update(
                "UPDATE conduct.records SET category=?,severity=?,title=?,body=?,visibility=?,classroom_id=?,subject_id=?," +
                        "occurred_at=?,version=version+1,updated_at=NOW() WHERE id=? AND school_id=? AND student_id=? AND version=?",
                values.category(), values.severity(), values.title(), values.body(), values.visibility(),
                values.classroomId(), values.subjectId(), values.occurredAt(), id, schoolId, studentId, expectedVersion);
        if (updated != 1) {
            throw ApiException.conflict("VERSION_CONFLICT", "Conduct record was changed by another user");
        }

        ConductView next = requireRecord(schoolId, studentId, id, false);
        appendRevision(schoolId, id, current, next, actor, safeReason);
        audit.append(schoolId, actor, "UPDATE", "CONDUCT_RECORD", id, snapshot(current), snapshot(next), safeReason);
        notifyAudience(schoolId, next, "student.conduct.updated", actor);
        return next;
    }

    public List<ConductView> list(UUID schoolId, UUID studentId, UUID actor) {
        requireStudent(schoolId, studentId);
        access.requireStudentView(schoolId, actor, studentId);
        String visibility = visibilityPredicate(schoolId, studentId, actor);
        return jdbc.query(
                "SELECT r.*,u.full_name recorded_by_name,c.name classroom_name,s.name subject_name " +
                        "FROM conduct.records r JOIN identity.users u ON u.id=r.recorded_by " +
                        "LEFT JOIN academic.classrooms c ON c.id=r.classroom_id LEFT JOIN academic.subjects s ON s.id=r.subject_id " +
                        "WHERE r.school_id=? AND r.student_id=? " + visibility +
                        " ORDER BY r.occurred_at DESC,r.id DESC LIMIT 200",
                (rs, i) -> map(rs), schoolId, studentId);
    }

    public List<Map<String, Object>> revisions(UUID schoolId, UUID studentId, UUID id, UUID actor) {
        requireStudent(schoolId, studentId);
        access.requireTeacherOfStudentOrAdmin(schoolId, actor, studentId);
        requireRecord(schoolId, studentId, id, false);
        return jdbc.queryForList(
                "SELECT r.id,r.version_before,r.version_after,r.previous_values,r.new_values,r.reason,r.correlation_id," +
                        "r.created_at,r.actor_user_id,u.full_name actor_name FROM conduct.revisions r " +
                        "JOIN identity.users u ON u.id=r.actor_user_id WHERE r.school_id=? AND r.conduct_record_id=? " +
                        "ORDER BY r.created_at DESC,r.id DESC",
                schoolId, id);
    }

    private Values validate(UUID schoolId, UUID studentId, Command command, UUID actor) {
        if (command == null) throw ApiException.badRequest("INVALID_CONDUCT", "Conduct details are required");
        String category = normalize(command.category(), CATEGORIES, "INVALID_CONDUCT_CATEGORY");
        String severity = command.severity() == null || command.severity().isBlank()
                ? null : normalize(command.severity(), SEVERITIES, "INVALID_CONDUCT_SEVERITY");
        String visibility = normalize(command.visibility(), VISIBILITIES, "INVALID_CONDUCT_VISIBILITY");
        String title = requireText(command.title(), 255, "INVALID_CONDUCT_TITLE", "Conduct title is required");
        String body = requireText(command.body(), 5000, "INVALID_CONDUCT_BODY", "Conduct note is required");
        OffsetDateTime occurredAt = command.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : command.occurredAt();
        if (occurredAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5))) {
            throw ApiException.badRequest("INVALID_CONDUCT_TIME", "Conduct time cannot be in the future");
        }
        validateContext(schoolId, studentId, command.classroomId(), command.subjectId(), actor);
        return new Values(category, severity, title, body, visibility, command.classroomId(), command.subjectId(), occurredAt);
    }

    private void validateContext(UUID schoolId, UUID studentId, UUID classroomId, UUID subjectId, UUID actor) {
        if (classroomId != null) {
            Integer enrolled = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM academic.class_enrollments e JOIN academic.classrooms c ON c.id=e.classroom_id " +
                            "WHERE e.school_id=? AND e.student_id=? AND e.classroom_id=? AND e.status='ACTIVE' AND c.status='ACTIVE'",
                    Integer.class, schoolId, studentId, classroomId);
            if (enrolled == null || enrolled == 0) {
                throw ApiException.badRequest("INVALID_CONDUCT_CLASSROOM", "Student is not actively enrolled in this classroom");
            }
            access.requireClassTeacherOrAdmin(schoolId, actor, classroomId);
        }
        if (subjectId != null) {
            Integer subject = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM academic.subjects WHERE id=? AND school_id=?",
                    Integer.class, subjectId, schoolId);
            if (subject == null || subject == 0) {
                throw ApiException.badRequest("INVALID_CONDUCT_SUBJECT", "Subject is not in this school");
            }
            if (!access.isPlatformAdmin(actor) && !access.hasRole(schoolId, actor, "SCHOOL_ADMIN")) {
                Object[] args = classroomId == null
                        ? new Object[]{schoolId, subjectId, actor, studentId}
                        : new Object[]{schoolId, subjectId, actor, studentId, classroomId};
                Integer assigned = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM academic.teaching_assignments ta " +
                                "JOIN academic.teachers t ON t.id=ta.teacher_id AND t.status='ACTIVE' " +
                                "JOIN academic.class_enrollments e ON e.classroom_id=ta.classroom_id AND e.status='ACTIVE' " +
                                "WHERE ta.school_id=? AND ta.subject_id=? AND ta.status='ACTIVE' AND t.user_id=? AND e.student_id=? " +
                                (classroomId == null ? "" : "AND ta.classroom_id=?"),
                        Integer.class, args);
                if (assigned == null || assigned == 0) {
                    throw ApiException.forbidden("Teacher is not assigned to this subject for the student");
                }
            }
        }
    }

    private void requireStudent(UUID schoolId, UUID studentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic.students WHERE id=? AND school_id=? AND status='ACTIVE'",
                Integer.class, studentId, schoolId);
        if (count == null || count == 0) throw ApiException.notFound("Student was not found");
    }

    private ConductView requireRecord(UUID schoolId, UUID studentId, UUID id, boolean lock) {
        List<ConductView> rows = jdbc.query(
                "SELECT r.*,u.full_name recorded_by_name,c.name classroom_name,s.name subject_name " +
                        "FROM conduct.records r JOIN identity.users u ON u.id=r.recorded_by " +
                        "LEFT JOIN academic.classrooms c ON c.id=r.classroom_id LEFT JOIN academic.subjects s ON s.id=r.subject_id " +
                        "WHERE r.id=? AND r.school_id=? AND r.student_id=?" + (lock ? " FOR UPDATE OF r" : ""),
                (rs, i) -> map(rs), id, schoolId, studentId);
        if (rows.isEmpty()) throw ApiException.notFound("Conduct record was not found");
        return rows.getFirst();
    }

    private ConductView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConductView(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("student_id")),
                rs.getString("category"), rs.getString("severity"), rs.getString("title"), rs.getString("body"),
                rs.getString("visibility"),
                rs.getString("classroom_id") == null ? null : UUID.fromString(rs.getString("classroom_id")),
                rs.getString("subject_id") == null ? null : UUID.fromString(rs.getString("subject_id")),
                rs.getString("classroom_name"), rs.getString("subject_name"),
                rs.getTimestamp("occurred_at").toInstant().atOffset(ZoneOffset.UTC),
                UUID.fromString(rs.getString("recorded_by")), rs.getString("recorded_by_name"),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC),
                rs.getTimestamp("updated_at").toInstant().atOffset(ZoneOffset.UTC));
    }

    private void appendRevision(UUID schoolId, UUID id, ConductView before, ConductView after, UUID actor, String reason) {
        try {
            jdbc.update(
                    "INSERT INTO conduct.revisions(id,school_id,conduct_record_id,version_before,version_after,previous_values,new_values," +
                            "actor_user_id,reason,correlation_id) VALUES (?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?)",
                    UUID.randomUUID(), schoolId, id, before.version(), after.version(),
                    json.writeValueAsString(snapshot(before)), json.writeValueAsString(snapshot(after)), actor, reason,
                    MDC.get(CorrelationIdFilter.MDC_KEY));
        } catch (Exception e) {
            throw new IllegalStateException("Conduct revision serialization failed", e);
        }
    }

    private void notifyAudience(UUID schoolId, ConductView record, String eventType, UUID actor) {
        LinkedHashSet<UUID> recipients = new LinkedHashSet<>();
        if ("GUARDIAN".equals(record.visibility()) || "STUDENT_AND_GUARDIAN".equals(record.visibility())) {
            recipients.addAll(notifications.guardianRecipients(schoolId, record.studentId()));
        }
        if ("STUDENT".equals(record.visibility()) || "STUDENT_AND_GUARDIAN".equals(record.visibility())) {
            recipients.addAll(jdbc.query(
                    "SELECT user_id FROM academic.students WHERE id=? AND school_id=? AND user_id IS NOT NULL AND status='ACTIVE'",
                    (rs, i) -> UUID.fromString(rs.getString(1)), record.studentId(), schoolId));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("studentId", record.studentId());
        data.put("category", record.category());
        data.put("severity", record.severity());
        data.put("visibility", record.visibility());
        data.put("version", record.version());
        data.put("actorUserId", actor);
        notifications.notifyUsers(
                schoolId, new ArrayList<>(recipients), eventType, record.title(), record.body(),
                "CONDUCT_RECORD", record.id(), data);
    }

    private String visibilityPredicate(UUID schoolId, UUID studentId, UUID actor) {
        if (access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN") ||
                access.isTeacherOfStudent(schoolId, actor, studentId)) {
            return "";
        }
        boolean guardian = access.isGuardianOf(schoolId, actor, studentId);
        boolean self = access.isStudentSelf(schoolId, actor, studentId);
        if (guardian && self) {
            return "AND r.visibility IN ('GUARDIAN','STUDENT','STUDENT_AND_GUARDIAN')";
        }
        if (guardian) {
            return "AND r.visibility IN ('GUARDIAN','STUDENT_AND_GUARDIAN')";
        }
        return "AND r.visibility IN ('STUDENT','STUDENT_AND_GUARDIAN')";
    }

    private boolean same(ConductView current, Values next) {
        return Objects.equals(current.category(), next.category()) && Objects.equals(current.severity(), next.severity()) &&
                Objects.equals(current.title(), next.title()) && Objects.equals(current.body(), next.body()) &&
                Objects.equals(current.visibility(), next.visibility()) && Objects.equals(current.classroomId(), next.classroomId()) &&
                Objects.equals(current.subjectId(), next.subjectId()) && current.occurredAt().toInstant().equals(next.occurredAt().toInstant());
    }

    private Map<String, Object> snapshot(ConductView value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("category", value.category());
        map.put("severity", value.severity());
        map.put("title", value.title());
        map.put("body", value.body());
        map.put("visibility", value.visibility());
        map.put("classroomId", value.classroomId());
        map.put("subjectId", value.subjectId());
        map.put("occurredAt", value.occurredAt());
        map.put("version", value.version());
        return map;
    }

    private String normalize(String value, Set<String> allowed, String code) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!allowed.contains(normalized)) throw ApiException.badRequest(code, "Unsupported value: " + normalized);
        return normalized;
    }

    private String requireText(String value, int max, String code, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) throw ApiException.badRequest(code, message);
        return normalized;
    }

    private record Values(String category, String severity, String title, String body, String visibility,
                          UUID classroomId, UUID subjectId, OffsetDateTime occurredAt) {}

    public record Command(String category, String severity, String title, String body, String visibility,
                          UUID classroomId, UUID subjectId, OffsetDateTime occurredAt) {}

    public record ConductView(UUID id, UUID studentId, String category, String severity, String title, String body,
                              String visibility, UUID classroomId, UUID subjectId, String classroomName, String subjectName,
                              OffsetDateTime occurredAt, UUID recordedBy, String recordedByName, long version,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
