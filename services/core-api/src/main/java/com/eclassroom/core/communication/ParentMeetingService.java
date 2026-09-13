package com.eclassroom.core.communication;

import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ParentMeetingService {
    private static final Set<String> SCOPES = Set.of("SCHOOL", "CLASSROOM", "STUDENT");
    private static final Set<String> RESPONSES = Set.of("ACCEPTED", "DECLINED");
    private static final Set<String> ATTENDANCE = Set.of("PRESENT", "ABSENT");
    private static final Set<String> OUTCOME_VISIBILITY = Set.of("STAFF_ONLY", "GUARDIAN", "STUDENT_AND_GUARDIAN");

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final NotificationService notifications;
    private final AuditService audit;

    public ParentMeetingService(JdbcTemplate jdbc, AccessService access,
                                NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public UUID create(UUID schoolId, CreateCommand command, UUID actor) {
        access.requireMembership(schoolId, actor);
        if (command == null) throw ApiException.badRequest("INVALID_MEETING", "Meeting details are required");
        String scope = normalize(command.scopeType(), SCOPES, "INVALID_MEETING_SCOPE");
        authorizeScope(schoolId, scope, command.scopeId(), actor);

        String title = requireText(command.title(), 255, "INVALID_MEETING_TITLE", "Meeting title is required");
        String agenda = requireText(command.agenda(), 10000, "INVALID_MEETING_AGENDA", "Meeting agenda is required");
        String location = requireText(command.location(), 255, "INVALID_MEETING_LOCATION", "Meeting location is required");
        String note = optionalText(command.note(), 10000, "INVALID_MEETING_NOTE");
        OffsetDateTime startsAt = command.startsAt();
        OffsetDateTime endsAt = command.endsAt();
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw ApiException.badRequest("INVALID_MEETING_TIME", "Meeting end time must be after start time");
        }
        if (startsAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5))) {
            throw ApiException.badRequest("INVALID_MEETING_TIME", "A new meeting cannot start in the past");
        }
        List<SlotCommand> slots = validateSlots(command.slots(), startsAt, endsAt);

        List<UUID> students = targetStudents(schoolId, scope, command.scopeId());
        if (students.isEmpty()) {
            throw ApiException.badRequest("MEETING_EMPTY_AUDIENCE", "Meeting scope does not contain any active students");
        }

        UUID meetingId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO communication.parent_meetings" +
                        "(id,school_id,scope_type,scope_id,title,agenda,note,location,starts_at,ends_at,include_students,created_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                meetingId, schoolId, scope, command.scopeId(), title, agenda, note, location,
                startsAt, endsAt, command.includeStudents(), actor);

        List<Object[]> studentRows = students.stream()
                .map(studentId -> new Object[]{schoolId, meetingId, studentId})
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO communication.meeting_students(school_id,meeting_id,student_id) VALUES (?,?,?)",
                studentRows);

        List<InviteTarget> invitees = deriveInvitees(schoolId, meetingId);
        if (invitees.isEmpty() && !command.includeStudents()) {
            throw ApiException.badRequest("MEETING_NO_GUARDIANS", "Meeting scope has no active guardian accounts to invite");
        }
        if (!invitees.isEmpty()) {
            List<Object[]> rows = invitees.stream()
                    .map(target -> new Object[]{UUID.randomUUID(), schoolId, meetingId, target.guardianUserId(), target.studentId()})
                    .toList();
            jdbc.batchUpdate(
                    "INSERT INTO communication.meeting_invitees(id,school_id,meeting_id,guardian_user_id,student_id) VALUES (?,?,?,?,?)",
                    rows);
        }

        if (!slots.isEmpty()) {
            List<Object[]> rows = slots.stream()
                    .map(slot -> new Object[]{UUID.randomUUID(), schoolId, meetingId, slot.startsAt(), slot.endsAt()})
                    .toList();
            jdbc.batchUpdate(
                    "INSERT INTO communication.meeting_slots(id,school_id,meeting_id,starts_at,ends_at) VALUES (?,?,?,?,?)",
                    rows);
        }

        MeetingView created = requireMeeting(meetingId);
        audit.append(schoolId, actor, "CREATE", "PARENT_MEETING", meetingId, null,
                auditMap(
                        "scopeType", scope,
                        "scopeId", command.scopeId(),
                        "studentCount", students.size(),
                        "inviteeCount", invitees.size(),
                        "slotCount", slots.size(),
                        "startsAt", startsAt,
                        "endsAt", endsAt), null);
        notifyMeetingAudience(created, "meeting.invited", "Lời mời họp phụ huynh",
                title + " · " + location, Map.of("action", "INVITATION"));
        return meetingId;
    }

    public List<MeetingView> list(UUID schoolId, UUID actor) {
        access.requireMembership(schoolId, actor);
        boolean admin = access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN");
        boolean teacher = access.hasRole(schoolId, actor, "TEACHER");
        return jdbc.query(
                "SELECT m.* FROM communication.parent_meetings m WHERE m.school_id=? AND (" +
                        "? OR ( ? AND (m.scope_type='SCHOOL' OR " +
                        "(m.scope_type='CLASSROOM' AND EXISTS (SELECT 1 FROM academic.teachers t " +
                        "LEFT JOIN academic.classrooms c ON c.homeroom_teacher_id=t.id " +
                        "LEFT JOIN academic.teaching_assignments ta ON ta.teacher_id=t.id AND ta.classroom_id=m.scope_id AND ta.status='ACTIVE' " +
                        "WHERE t.school_id=m.school_id AND t.user_id=? AND t.status='ACTIVE' AND (c.id=m.scope_id OR ta.id IS NOT NULL))) OR " +
                        "(m.scope_type='STUDENT' AND EXISTS (SELECT 1 FROM academic.teachers t " +
                        "JOIN academic.teaching_assignments ta ON ta.teacher_id=t.id AND ta.status='ACTIVE' " +
                        "JOIN academic.class_enrollments e ON e.classroom_id=ta.classroom_id AND e.status='ACTIVE' " +
                        "WHERE t.school_id=m.school_id AND t.user_id=? AND t.status='ACTIVE' AND e.student_id=m.scope_id)))) OR " +
                        "EXISTS (SELECT 1 FROM communication.meeting_invitees mi WHERE mi.meeting_id=m.id AND mi.guardian_user_id=?) OR " +
                        "(m.include_students=TRUE AND EXISTS (SELECT 1 FROM communication.meeting_students ms " +
                        "JOIN academic.students s ON s.id=ms.student_id WHERE ms.meeting_id=m.id AND s.user_id=? AND s.status='ACTIVE'))" +
                        ") ORDER BY m.starts_at DESC,m.id DESC LIMIT 200",
                (rs, i) -> mapMeeting(rs), schoolId, admin, teacher, actor, actor, actor, actor);
    }

    public MeetingDetails details(UUID meetingId, UUID actor) {
        MeetingView meeting = requireMeeting(meetingId);
        access.requireMembership(meeting.schoolId(), actor);
        requireCanView(meeting, actor);
        boolean manager = canManage(meeting, actor);
        boolean parent = hasInvitee(meetingId, actor);
        boolean student = isMeetingStudent(meetingId, actor) && meeting.includeStudents();

        List<InviteeView> invitees = manager ? invitees(meetingId, null) : parent ? invitees(meetingId, actor) : List.of();
        List<SlotView> slots = slotViews(meetingId, actor, manager, parent);
        List<OutcomeView> outcomes = outcomeViews(meeting, actor, manager, parent, student);
        return new MeetingDetails(meeting, invitees, slots, outcomes, manager);
    }

    @Transactional
    public InviteeView respond(UUID meetingId, UUID studentId, String response, long expectedVersion, UUID actor) {
        MeetingView meeting = requireMeeting(meetingId);
        access.requireMembership(meeting.schoolId(), actor);
        ensureScheduled(meeting);
        String normalized = normalize(response, RESPONSES, "INVALID_MEETING_RESPONSE");
        InviteeView current = requireGuardianInvitee(meetingId, studentId, actor, true);
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "Meeting response changed; reload before responding");
        }
        if (normalized.equals(current.response())) return current;

        int updated = jdbc.update(
                "UPDATE communication.meeting_invitees SET response=?,responded_at=NOW(),version=version+1 " +
                        "WHERE id=? AND version=?",
                normalized, current.id(), expectedVersion);
        if (updated != 1) throw ApiException.conflict("VERSION_CONFLICT", "Meeting response changed; reload before responding");

        if ("DECLINED".equals(normalized)) {
            jdbc.update(
                    "UPDATE communication.meeting_slots SET booked_by_guardian_user_id=NULL,booked_for_student_id=NULL," +
                            "version=version+1 WHERE meeting_id=? AND booked_by_guardian_user_id=? AND booked_for_student_id=?",
                    meetingId, actor, studentId);
        }
        InviteeView next = requireInviteeById(current.id(), false);
        audit.append(meeting.schoolId(), actor, "RESPOND", "MEETING_INVITEE", current.id(),
                auditMap("response", current.response(), "version", current.version()),
                auditMap("response", next.response(), "version", next.version()), null);
        notifyCreator(meeting, "meeting.response.updated", "Phản hồi lời mời họp",
                next.guardianName() + " đã phản hồi: " + next.response(), Map.of("studentId", studentId));
        return next;
    }

    @Transactional
    public SlotView bookSlot(UUID meetingId, UUID slotId, UUID studentId, long expectedVersion, UUID actor) {
        MeetingView meeting = requireMeeting(meetingId);
        access.requireMembership(meeting.schoolId(), actor);
        ensureScheduled(meeting);
        InviteeView invitee = requireGuardianInvitee(meetingId, studentId, actor, false);
        if (!"ACCEPTED".equals(invitee.response())) {
            throw ApiException.badRequest("MEETING_RESPONSE_REQUIRED", "Accept the meeting invitation before booking a slot");
        }
        SlotRow slot = requireSlot(meetingId, slotId, true);
        if (slot.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "Meeting slot changed; reload before booking");
        }
        if (actor.equals(slot.guardianUserId()) && studentId.equals(slot.studentId())) {
            return slotView(slot, false, true);
        }
        if (slot.guardianUserId() != null) {
            throw ApiException.conflict("MEETING_SLOT_BOOKED", "This meeting slot has already been booked");
        }
        try {
            int updated = jdbc.update(
                    "UPDATE communication.meeting_slots SET booked_by_guardian_user_id=?,booked_for_student_id=?,version=version+1 " +
                            "WHERE id=? AND meeting_id=? AND version=? AND booked_by_guardian_user_id IS NULL",
                    actor, studentId, slotId, meetingId, expectedVersion);
            if (updated != 1) throw ApiException.conflict("MEETING_SLOT_BOOKED", "This meeting slot has already been booked");
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("MEETING_SLOT_ALREADY_HELD", "This guardian/student already has a slot for the meeting");
        }
        SlotRow next = requireSlot(meetingId, slotId, false);
        audit.append(meeting.schoolId(), actor, "BOOK_SLOT", "MEETING_SLOT", slotId, null,
                auditMap("studentId", studentId, "startsAt", next.startsAt(), "endsAt", next.endsAt()), null);
        notifyCreator(meeting, "meeting.slot.booked", "Đã đặt lịch trao đổi",
                invitee.guardianName() + " đã đặt khung giờ", Map.of("studentId", studentId, "slotId", slotId));
        return slotView(next, false, true);
    }

    @Transactional
    public SlotView cancelBooking(UUID meetingId, UUID slotId, UUID studentId, long expectedVersion, UUID actor) {
        MeetingView meeting = requireMeeting(meetingId);
        access.requireMembership(meeting.schoolId(), actor);
        ensureScheduled(meeting);
        requireGuardianInvitee(meetingId, studentId, actor, false);
        SlotRow slot = requireSlot(meetingId, slotId, true);
        if (slot.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "Meeting slot changed; reload before cancelling");
        }
        if (!actor.equals(slot.guardianUserId()) || !studentId.equals(slot.studentId())) {
            throw ApiException.forbidden("You do not own this meeting slot booking");
        }
        int updated = jdbc.update(
                "UPDATE communication.meeting_slots SET booked_by_guardian_user_id=NULL,booked_for_student_id=NULL,version=version+1 " +
                        "WHERE id=? AND meeting_id=? AND version=?",
                slotId, meetingId, expectedVersion);
        if (updated != 1) throw ApiException.conflict("VERSION_CONFLICT", "Meeting slot changed; reload before cancelling");
        SlotRow next = requireSlot(meetingId, slotId, false);
        audit.append(meeting.schoolId(), actor, "CANCEL_SLOT", "MEETING_SLOT", slotId,
                auditMap("studentId", studentId), auditMap("available", true), null);
        notifyCreator(meeting, "meeting.slot.cancelled", "Đã huỷ khung giờ",
                "Phụ huynh đã huỷ khung giờ trao đổi", Map.of("studentId", studentId, "slotId", slotId));
        return slotView(next, false, false);
    }

    @Transactional
    public InviteeView recordAttendance(UUID meetingId, UUID inviteeId, String attendance,
                                        long expectedVersion, UUID actor) {
        MeetingView meeting = requireMeeting(meetingId);
        requireManage(meeting, actor);
        if (OffsetDateTime.now(ZoneOffset.UTC).isBefore(meeting.startsAt())) {
            throw ApiException.badRequest("MEETING_NOT_STARTED", "Attendance can only be recorded after the meeting starts");
        }
        String normalized = normalize(attendance, ATTENDANCE, "INVALID_MEETING_ATTENDANCE");
        InviteeView current = requireInviteeById(inviteeId, true);
        if (!meetingId.equals(current.meetingId())) throw ApiException.notFound("Meeting invitee was not found");
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "Meeting attendance changed; reload before saving");
        }
        int updated = jdbc.update(
                "UPDATE communication.meeting_invitees SET attendance=?,attendance_recorded_by=?,attendance_recorded_at=NOW()," +
                        "version=version+1 WHERE id=? AND version=?",
                normalized, actor, inviteeId, expectedVersion);
        if (updated != 1) throw ApiException.conflict("VERSION_CONFLICT", "Meeting attendance changed; reload before saving");
        InviteeView next = requireInviteeById(inviteeId, false);
        audit.append(meeting.schoolId(), actor, "ATTENDANCE", "MEETING_INVITEE", inviteeId,
                auditMap("attendance", current.attendance()), auditMap("attendance", next.attendance()), null);
        return next;
    }

    @Transactional
    public UUID addOutcome(UUID meetingId, UUID studentId, String body, String visibility, UUID actor) {
        MeetingView meeting = requireMeeting(meetingId);
        requireManage(meeting, actor);
        String safeBody = requireText(body, 10000, "INVALID_MEETING_OUTCOME", "Outcome note is required");
        String safeVisibility = normalize(visibility, OUTCOME_VISIBILITY, "INVALID_MEETING_OUTCOME_VISIBILITY");
        if (!"STAFF_ONLY".equals(safeVisibility)) {
            if (studentId == null || !meetingHasStudent(meetingId, studentId)) {
                throw ApiException.badRequest("INVALID_MEETING_OUTCOME_STUDENT", "A student in this meeting is required for family-visible outcomes");
            }
        } else if (studentId != null && !meetingHasStudent(meetingId, studentId)) {
            throw ApiException.badRequest("INVALID_MEETING_OUTCOME_STUDENT", "Outcome student is not part of this meeting");
        }
        if ("STUDENT_AND_GUARDIAN".equals(safeVisibility) && !meeting.includeStudents()) {
            throw ApiException.badRequest("MEETING_STUDENT_VISIBILITY_DISABLED", "This meeting was not configured for student visibility");
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO communication.meeting_outcomes(id,school_id,meeting_id,student_id,body,visibility,recorded_by) VALUES (?,?,?,?,?,?,?)",
                id, meeting.schoolId(), meetingId, studentId, safeBody, safeVisibility, actor);
        audit.append(meeting.schoolId(), actor, "CREATE_OUTCOME", "MEETING_OUTCOME", id, null,
                auditMap("meetingId", meetingId, "studentId", studentId, "visibility", safeVisibility), null);

        if (!"STAFF_ONLY".equals(safeVisibility)) {
            List<UUID> recipients = new ArrayList<>(notificationRecipientsForStudent(meetingId, studentId));
            if ("STUDENT_AND_GUARDIAN".equals(safeVisibility)) recipients.addAll(studentRecipients(meetingId, studentId));
            notifications.notifyUsers(meeting.schoolId(), recipients, "meeting.outcome.created", "Kết quả buổi họp",
                    safeBody, "MEETING_OUTCOME", id, Map.of("meetingId", meetingId, "studentId", studentId));
        } else {
            notifications.notifyUsers(meeting.schoolId(), List.of(), "meeting.outcome.created", "Kết quả buổi họp",
                    safeBody, "MEETING_OUTCOME", id, Map.of("meetingId", meetingId));
        }
        return id;
    }

    @Transactional
    public int remind(UUID meetingId, UUID actor) {
        MeetingView meeting = requireMeeting(meetingId);
        requireManage(meeting, actor);
        ensureScheduled(meeting);
        if (!meeting.startsAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw ApiException.badRequest("MEETING_ALREADY_STARTED", "Only future meetings can send reminders");
        }
        List<UUID> inviteeIds = jdbc.query(
                "SELECT id FROM communication.meeting_invitees WHERE meeting_id=? AND response<>'DECLINED' " +
                        "AND (last_reminded_at IS NULL OR last_reminded_at < NOW()-INTERVAL '1 hour')",
                (rs, i) -> UUID.fromString(rs.getString(1)), meetingId);
        if (inviteeIds.isEmpty()) {
            throw ApiException.conflict("MEETING_REMINDER_TOO_SOON", "No eligible invitees can be reminded yet");
        }
        String placeholders = String.join(",", inviteeIds.stream().map(id -> "?").toList());
        jdbc.update("UPDATE communication.meeting_invitees SET last_reminded_at=NOW() WHERE id IN (" + placeholders + ")",
                inviteeIds.toArray());

        List<UUID> recipients = notificationRecipientsForInvitees(inviteeIds);
        notifications.notifyUsers(meeting.schoolId(), recipients, "meeting.reminder", "Nhắc lịch họp phụ huynh",
                meeting.title() + " · " + meeting.location(), "PARENT_MEETING", meeting.id(),
                Map.of("startsAt", meeting.startsAt(), "location", meeting.location(), "inviteeCount", inviteeIds.size()));
        audit.append(meeting.schoolId(), actor, "REMIND", "PARENT_MEETING", meeting.id(), null,
                auditMap("inviteeCount", inviteeIds.size()), null);
        return inviteeIds.size();
    }

    @Transactional
    public MeetingView cancel(UUID meetingId, long expectedVersion, UUID actor) {
        MeetingView meeting = requireMeeting(meetingId);
        requireManage(meeting, actor);
        if ("CANCELLED".equals(meeting.status())) return meeting;
        if (!"SCHEDULED".equals(meeting.status())) {
            throw ApiException.conflict("INVALID_MEETING_STATE", "Only scheduled meetings can be cancelled");
        }
        return transition(meeting, expectedVersion, "CANCELLED", actor, "meeting.cancelled", "Cuộc họp đã bị huỷ");
    }

    @Transactional
    public MeetingView complete(UUID meetingId, long expectedVersion, UUID actor) {
        MeetingView meeting = requireMeeting(meetingId);
        requireManage(meeting, actor);
        if ("COMPLETED".equals(meeting.status())) return meeting;
        if (!"SCHEDULED".equals(meeting.status())) {
            throw ApiException.conflict("INVALID_MEETING_STATE", "Only scheduled meetings can be completed");
        }
        if (OffsetDateTime.now(ZoneOffset.UTC).isBefore(meeting.endsAt())) {
            throw ApiException.badRequest("MEETING_NOT_ENDED", "Meeting can only be completed after its end time");
        }
        return transition(meeting, expectedVersion, "COMPLETED", actor, "meeting.completed", "Cuộc họp đã hoàn tất");
    }

    private MeetingView transition(MeetingView meeting, long expectedVersion, String target, UUID actor,
                                   String eventType, String eventTitle) {
        if (meeting.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "Meeting changed; reload before changing status");
        }
        int updated = jdbc.update(
                "UPDATE communication.parent_meetings SET status=?,version=version+1,updated_at=NOW() WHERE id=? AND version=?",
                target, meeting.id(), expectedVersion);
        if (updated != 1) throw ApiException.conflict("VERSION_CONFLICT", "Meeting changed; reload before changing status");
        MeetingView next = requireMeeting(meeting.id());
        audit.append(meeting.schoolId(), actor, target, "PARENT_MEETING", meeting.id(),
                auditMap("status", meeting.status(), "version", meeting.version()),
                auditMap("status", next.status(), "version", next.version()), null);
        notifyMeetingAudience(next, eventType, eventTitle, next.title(), Map.of("status", target));
        return next;
    }

    private void authorizeScope(UUID schoolId, String scope, UUID scopeId, UUID actor) {
        if ("SCHOOL".equals(scope)) {
            if (scopeId != null) throw ApiException.badRequest("INVALID_MEETING_SCOPE", "School meetings do not use scopeId");
            access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN");
            return;
        }
        if (scopeId == null) throw ApiException.badRequest("INVALID_MEETING_SCOPE", "Classroom/student meetings require scopeId");
        if ("CLASSROOM".equals(scope)) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM academic.classrooms WHERE id=? AND school_id=? AND status='ACTIVE'",
                    Integer.class, scopeId, schoolId);
            if (exists == null || exists == 0) throw ApiException.notFound("Classroom was not found");
            access.requireClassTeacherOrAdmin(schoolId, actor, scopeId);
        } else {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM academic.students WHERE id=? AND school_id=? AND status='ACTIVE'",
                    Integer.class, scopeId, schoolId);
            if (exists == null || exists == 0) throw ApiException.notFound("Student was not found");
            access.requireTeacherOfStudentOrAdmin(schoolId, actor, scopeId);
        }
    }

    private List<UUID> targetStudents(UUID schoolId, String scope, UUID scopeId) {
        if ("SCHOOL".equals(scope)) {
            return jdbc.query("SELECT id FROM academic.students WHERE school_id=? AND status='ACTIVE' ORDER BY id",
                    (rs, i) -> UUID.fromString(rs.getString(1)), schoolId);
        }
        if ("CLASSROOM".equals(scope)) {
            return jdbc.query(
                    "SELECT DISTINCT s.id FROM academic.class_enrollments e JOIN academic.students s ON s.id=e.student_id " +
                            "WHERE e.school_id=? AND e.classroom_id=? AND e.status='ACTIVE' AND s.status='ACTIVE' ORDER BY s.id",
                    (rs, i) -> UUID.fromString(rs.getString(1)), schoolId, scopeId);
        }
        return List.of(scopeId);
    }

    private List<InviteTarget> deriveInvitees(UUID schoolId, UUID meetingId) {
        return jdbc.query(
                "SELECT DISTINCT g.user_id,ms.student_id FROM communication.meeting_students ms " +
                        "JOIN academic.student_guardians sg ON sg.school_id=ms.school_id AND sg.student_id=ms.student_id " +
                        "JOIN academic.guardians g ON g.id=sg.guardian_id AND g.school_id=ms.school_id " +
                        "JOIN identity.users u ON u.id=g.user_id " +
                        "WHERE ms.school_id=? AND ms.meeting_id=? AND g.status='ACTIVE' AND u.status='ACTIVE' AND g.user_id IS NOT NULL",
                (rs, i) -> new InviteTarget(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2))),
                schoolId, meetingId);
    }

    private List<SlotCommand> validateSlots(List<SlotCommand> requested, OffsetDateTime meetingStart, OffsetDateTime meetingEnd) {
        if (requested == null || requested.isEmpty()) return List.of();
        if (requested.size() > 100) throw ApiException.badRequest("TOO_MANY_MEETING_SLOTS", "A meeting may define at most 100 slots");
        List<SlotCommand> slots = new ArrayList<>(requested);
        slots.sort(Comparator.comparing(SlotCommand::startsAt));
        OffsetDateTime previousEnd = null;
        for (SlotCommand slot : slots) {
            if (slot == null || slot.startsAt() == null || slot.endsAt() == null || !slot.endsAt().isAfter(slot.startsAt())) {
                throw ApiException.badRequest("INVALID_MEETING_SLOT", "Each meeting slot requires a valid start/end time");
            }
            if (slot.startsAt().isBefore(meetingStart) || slot.endsAt().isAfter(meetingEnd)) {
                throw ApiException.badRequest("INVALID_MEETING_SLOT", "Meeting slots must stay inside the meeting time range");
            }
            if (previousEnd != null && slot.startsAt().isBefore(previousEnd)) {
                throw ApiException.badRequest("OVERLAPPING_MEETING_SLOTS", "Meeting slots may not overlap");
            }
            previousEnd = slot.endsAt();
        }
        return List.copyOf(slots);
    }

    private MeetingView requireMeeting(UUID id) {
        List<MeetingView> rows = jdbc.query("SELECT * FROM communication.parent_meetings WHERE id=?",
                (rs, i) -> mapMeeting(rs), id);
        if (rows.isEmpty()) throw ApiException.notFound("Parent meeting was not found");
        return rows.getFirst();
    }

    private InviteeView requireGuardianInvitee(UUID meetingId, UUID studentId, UUID guardianUserId, boolean lock) {
        List<InviteeView> rows = jdbc.query(inviteeSql(
                        "WHERE mi.meeting_id=? AND mi.student_id=? AND mi.guardian_user_id=?" + (lock ? " FOR UPDATE OF mi" : "")),
                (rs, i) -> mapInvitee(rs), meetingId, studentId, guardianUserId);
        if (rows.isEmpty()) throw ApiException.forbidden("You are not invited to this meeting for the selected student");
        return rows.getFirst();
    }

    private InviteeView requireInviteeById(UUID inviteeId, boolean lock) {
        List<InviteeView> rows = jdbc.query(inviteeSql("WHERE mi.id=?" + (lock ? " FOR UPDATE OF mi" : "")),
                (rs, i) -> mapInvitee(rs), inviteeId);
        if (rows.isEmpty()) throw ApiException.notFound("Meeting invitee was not found");
        return rows.getFirst();
    }

    private List<InviteeView> invitees(UUID meetingId, UUID guardianUserId) {
        String where = guardianUserId == null ? "WHERE mi.meeting_id=?" : "WHERE mi.meeting_id=? AND mi.guardian_user_id=?";
        if (guardianUserId == null) {
            return jdbc.query(inviteeSql(where) + " ORDER BY s.full_name,g.full_name",
                    (rs, i) -> mapInvitee(rs), meetingId);
        }
        return jdbc.query(inviteeSql(where) + " ORDER BY s.full_name,g.full_name",
                (rs, i) -> mapInvitee(rs), meetingId, guardianUserId);
    }

    private String inviteeSql(String where) {
        return "SELECT mi.*,g.full_name guardian_name,s.full_name student_name FROM communication.meeting_invitees mi " +
                "JOIN academic.guardians g ON g.user_id=mi.guardian_user_id AND g.school_id=mi.school_id " +
                "JOIN academic.students s ON s.id=mi.student_id " + where;
    }

    private SlotRow requireSlot(UUID meetingId, UUID slotId, boolean lock) {
        List<SlotRow> rows = jdbc.query(
                "SELECT * FROM communication.meeting_slots WHERE id=? AND meeting_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> mapSlot(rs), slotId, meetingId);
        if (rows.isEmpty()) throw ApiException.notFound("Meeting slot was not found");
        return rows.getFirst();
    }

    private List<SlotView> slotViews(UUID meetingId, UUID actor, boolean manager, boolean parent) {
        List<SlotRow> rows = jdbc.query("SELECT * FROM communication.meeting_slots WHERE meeting_id=? ORDER BY starts_at,id",
                (rs, i) -> mapSlot(rs), meetingId);
        return rows.stream().map(row -> slotView(row, manager, parent && actor.equals(row.guardianUserId()))).toList();
    }

    private SlotView slotView(SlotRow row, boolean manager, boolean mine) {
        boolean available = row.guardianUserId() == null;
        return new SlotView(row.id(), row.startsAt(), row.endsAt(), available, row.version(), mine,
                manager ? row.guardianUserId() : null, (manager || mine) ? row.studentId() : null);
    }

    private List<OutcomeView> outcomeViews(MeetingView meeting, UUID actor, boolean manager, boolean parent, boolean student) {
        if (manager) {
            return jdbc.query(outcomeSql("WHERE o.meeting_id=?"), (rs, i) -> mapOutcome(rs), meeting.id());
        }
        if (parent) {
            return jdbc.query(outcomeSql(
                            "WHERE o.meeting_id=? AND o.visibility IN ('GUARDIAN','STUDENT_AND_GUARDIAN') " +
                                    "AND EXISTS (SELECT 1 FROM communication.meeting_invitees mi WHERE mi.meeting_id=o.meeting_id " +
                                    "AND mi.student_id=o.student_id AND mi.guardian_user_id=?)"),
                    (rs, i) -> mapOutcome(rs), meeting.id(), actor);
        }
        if (student) {
            return jdbc.query(outcomeSql(
                            "WHERE o.meeting_id=? AND o.visibility='STUDENT_AND_GUARDIAN' " +
                                    "AND EXISTS (SELECT 1 FROM academic.students s WHERE s.id=o.student_id AND s.user_id=?)"),
                    (rs, i) -> mapOutcome(rs), meeting.id(), actor);
        }
        return List.of();
    }

    private String outcomeSql(String where) {
        return "SELECT o.*,s.full_name student_name,u.full_name recorded_by_name FROM communication.meeting_outcomes o " +
                "LEFT JOIN academic.students s ON s.id=o.student_id JOIN identity.users u ON u.id=o.recorded_by " +
                where + " ORDER BY o.created_at DESC,o.id DESC";
    }

    private void requireCanView(MeetingView meeting, UUID actor) {
        if (canManage(meeting, actor)) return;
        if (access.hasRole(meeting.schoolId(), actor, "TEACHER") && "SCHOOL".equals(meeting.scopeType())) return;
        if (hasInvitee(meeting.id(), actor)) return;
        if (meeting.includeStudents() && isMeetingStudent(meeting.id(), actor)) return;
        throw ApiException.forbidden("You cannot view this parent meeting");
    }

    private boolean canManage(MeetingView meeting, UUID actor) {
        try {
            requireManage(meeting, actor);
            return true;
        } catch (ApiException ignored) {
            return false;
        }
    }

    private void requireManage(MeetingView meeting, UUID actor) {
        access.requireMembership(meeting.schoolId(), actor);
        if (access.isPlatformAdmin(actor) || access.hasRole(meeting.schoolId(), actor, "SCHOOL_ADMIN")) return;
        if ("SCHOOL".equals(meeting.scopeType())) throw ApiException.forbidden("Only a school admin can manage school-wide parent meetings");
        if ("CLASSROOM".equals(meeting.scopeType())) {
            access.requireClassTeacherOrAdmin(meeting.schoolId(), actor, meeting.scopeId());
        } else {
            access.requireTeacherOfStudentOrAdmin(meeting.schoolId(), actor, meeting.scopeId());
        }
    }

    private boolean hasInvitee(UUID meetingId, UUID actor) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM communication.meeting_invitees WHERE meeting_id=? AND guardian_user_id=?",
                Integer.class, meetingId, actor);
        return count != null && count > 0;
    }

    private boolean isMeetingStudent(UUID meetingId, UUID actor) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM communication.meeting_students ms JOIN academic.students s ON s.id=ms.student_id " +
                        "WHERE ms.meeting_id=? AND s.user_id=? AND s.status='ACTIVE'",
                Integer.class, meetingId, actor);
        return count != null && count > 0;
    }

    private boolean meetingHasStudent(UUID meetingId, UUID studentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM communication.meeting_students WHERE meeting_id=? AND student_id=?",
                Integer.class, meetingId, studentId);
        return count != null && count > 0;
    }

    private void ensureScheduled(MeetingView meeting) {
        if (!"SCHEDULED".equals(meeting.status())) {
            throw ApiException.conflict("INVALID_MEETING_STATE", "Meeting is no longer scheduled");
        }
    }

    private List<UUID> notificationRecipients(UUID meetingId) {
        return jdbc.query(
                "SELECT DISTINCT mi.guardian_user_id FROM communication.meeting_invitees mi " +
                        "JOIN academic.guardians g ON g.user_id=mi.guardian_user_id AND g.school_id=mi.school_id " +
                        "JOIN academic.student_guardians sg ON sg.guardian_id=g.id AND sg.student_id=mi.student_id " +
                        "WHERE mi.meeting_id=? AND sg.notifications_enabled=TRUE",
                (rs, i) -> UUID.fromString(rs.getString(1)), meetingId);
    }

    private List<UUID> notificationRecipientsForStudent(UUID meetingId, UUID studentId) {
        return jdbc.query(
                "SELECT DISTINCT mi.guardian_user_id FROM communication.meeting_invitees mi " +
                        "JOIN academic.guardians g ON g.user_id=mi.guardian_user_id AND g.school_id=mi.school_id " +
                        "JOIN academic.student_guardians sg ON sg.guardian_id=g.id AND sg.student_id=mi.student_id " +
                        "WHERE mi.meeting_id=? AND mi.student_id=? AND sg.notifications_enabled=TRUE",
                (rs, i) -> UUID.fromString(rs.getString(1)), meetingId, studentId);
    }

    private List<UUID> notificationRecipientsForInvitees(List<UUID> inviteeIds) {
        if (inviteeIds.isEmpty()) return List.of();
        String placeholders = String.join(",", inviteeIds.stream().map(id -> "?").toList());
        return jdbc.query(
                "SELECT DISTINCT mi.guardian_user_id FROM communication.meeting_invitees mi " +
                        "JOIN academic.guardians g ON g.user_id=mi.guardian_user_id AND g.school_id=mi.school_id " +
                        "JOIN academic.student_guardians sg ON sg.guardian_id=g.id AND sg.student_id=mi.student_id " +
                        "WHERE mi.id IN (" + placeholders + ") AND sg.notifications_enabled=TRUE",
                (rs, i) -> UUID.fromString(rs.getString(1)), inviteeIds.toArray());
    }

    private List<UUID> studentRecipients(UUID meetingId, UUID onlyStudentId) {
        return jdbc.query(
                "SELECT DISTINCT s.user_id FROM communication.meeting_students ms JOIN academic.students s ON s.id=ms.student_id " +
                        "WHERE ms.meeting_id=? AND s.status='ACTIVE' AND s.user_id IS NOT NULL " +
                        (onlyStudentId == null ? "" : "AND s.id=?"),
                (rs, i) -> UUID.fromString(rs.getString(1)),
                onlyStudentId == null ? new Object[]{meetingId} : new Object[]{meetingId, onlyStudentId});
    }

    private void notifyMeetingAudience(MeetingView meeting, String eventType, String title, String body,
                                       Map<String, Object> extra) {
        LinkedHashSet<UUID> recipients = new LinkedHashSet<>(notificationRecipients(meeting.id()));
        if (meeting.includeStudents()) recipients.addAll(studentRecipients(meeting.id(), null));
        Map<String, Object> data = new LinkedHashMap<>(extra);
        data.put("scopeType", meeting.scopeType());
        data.put("scopeId", meeting.scopeId());
        data.put("startsAt", meeting.startsAt());
        data.put("endsAt", meeting.endsAt());
        data.put("location", meeting.location());
        notifications.notifyUsers(meeting.schoolId(), new ArrayList<>(recipients), eventType, title, body,
                "PARENT_MEETING", meeting.id(), data);
    }

    private void notifyCreator(MeetingView meeting, String eventType, String title, String body,
                               Map<String, Object> extra) {
        notifications.notifyUsers(meeting.schoolId(), List.of(meeting.createdBy()), eventType, title, body,
                "PARENT_MEETING", meeting.id(), extra);
    }

    private MeetingView mapMeeting(ResultSet rs) throws SQLException {
        return new MeetingView(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("school_id")),
                rs.getString("scope_type"), rs.getString("scope_id") == null ? null : UUID.fromString(rs.getString("scope_id")),
                rs.getString("title"), rs.getString("agenda"), rs.getString("note"), rs.getString("location"),
                timestamp(rs, "starts_at"), timestamp(rs, "ends_at"), rs.getBoolean("include_students"),
                rs.getString("status"), UUID.fromString(rs.getString("created_by")), rs.getLong("version"),
                timestamp(rs, "created_at"), timestamp(rs, "updated_at"));
    }

    private InviteeView mapInvitee(ResultSet rs) throws SQLException {
        Timestamp responded = rs.getTimestamp("responded_at");
        Timestamp attendanceAt = rs.getTimestamp("attendance_recorded_at");
        Timestamp reminded = rs.getTimestamp("last_reminded_at");
        return new InviteeView(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("meeting_id")),
                UUID.fromString(rs.getString("guardian_user_id")), UUID.fromString(rs.getString("student_id")),
                rs.getString("guardian_name"), rs.getString("student_name"), rs.getString("response"),
                responded == null ? null : responded.toInstant().atOffset(ZoneOffset.UTC), rs.getString("attendance"),
                attendanceAt == null ? null : attendanceAt.toInstant().atOffset(ZoneOffset.UTC),
                reminded == null ? null : reminded.toInstant().atOffset(ZoneOffset.UTC), rs.getLong("version"));
    }

    private SlotRow mapSlot(ResultSet rs) throws SQLException {
        return new SlotRow(UUID.fromString(rs.getString("id")), timestamp(rs, "starts_at"), timestamp(rs, "ends_at"),
                rs.getString("booked_by_guardian_user_id") == null ? null : UUID.fromString(rs.getString("booked_by_guardian_user_id")),
                rs.getString("booked_for_student_id") == null ? null : UUID.fromString(rs.getString("booked_for_student_id")),
                rs.getLong("version"));
    }

    private OutcomeView mapOutcome(ResultSet rs) throws SQLException {
        return new OutcomeView(UUID.fromString(rs.getString("id")),
                rs.getString("student_id") == null ? null : UUID.fromString(rs.getString("student_id")),
                rs.getString("student_name"), rs.getString("body"), rs.getString("visibility"),
                rs.getString("recorded_by_name"), timestamp(rs, "created_at"));
    }

    private OffsetDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private Map<String, Object> auditMap(Object... values) {
        if (values.length % 2 != 0) throw new IllegalArgumentException("Audit map requires key/value pairs");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
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

    private String optionalText(String value, int max, String code) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw ApiException.badRequest(code, "Value exceeds maximum length");
        return normalized;
    }

    private record InviteTarget(UUID guardianUserId, UUID studentId) {}
    private record SlotRow(UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt,
                           UUID guardianUserId, UUID studentId, long version) {}

    public record SlotCommand(OffsetDateTime startsAt, OffsetDateTime endsAt) {}
    public record CreateCommand(String scopeType, UUID scopeId, String title, String agenda, String note,
                                String location, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                boolean includeStudents, List<SlotCommand> slots) {}
    public record MeetingView(UUID id, UUID schoolId, String scopeType, UUID scopeId, String title, String agenda,
                              String note, String location, OffsetDateTime startsAt, OffsetDateTime endsAt,
                              boolean includeStudents, String status, UUID createdBy, long version,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record InviteeView(UUID id, UUID meetingId, UUID guardianUserId, UUID studentId,
                              String guardianName, String studentName, String response, OffsetDateTime respondedAt,
                              String attendance, OffsetDateTime attendanceRecordedAt,
                              OffsetDateTime lastRemindedAt, long version) {}
    public record SlotView(UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt, boolean available,
                           long version, boolean mine, UUID guardianUserId, UUID studentId) {}
    public record OutcomeView(UUID id, UUID studentId, String studentName, String body, String visibility,
                              String recordedByName, OffsetDateTime createdAt) {}
    public record MeetingDetails(MeetingView meeting, List<InviteeView> invitees, List<SlotView> slots,
                                 List<OutcomeView> outcomes, boolean manageable) {}
}
