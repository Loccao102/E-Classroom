package com.eclassroom.core;

import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.communication.ParentMeetingService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.integration.OutboxService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.ApiException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class ParentMeetingWorkflowIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_meeting_test")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static ParentMeetingService meetings;

    @BeforeAll
    static void setUpDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        jdbc = new JdbcTemplate(dataSource);
        JsonMapper json = JsonMapper.builder().build();
        AccessService access = new AccessService(jdbc);
        NotificationService notifications = new NotificationService(jdbc, new OutboxService(jdbc, json));
        meetings = new ParentMeetingService(jdbc, access, notifications, new AuditService(jdbc, json));
    }

    @Test
    void classMeetingSnapshotsAudienceAndAppliesRoleAwareViews() {
        Fixture f = fixture("AUD");
        UUID meetingId = createClassMeeting(f, true, true);

        assertEquals(2, count("SELECT COUNT(*) FROM communication.meeting_students WHERE meeting_id=?", meetingId));
        assertEquals(2, count("SELECT COUNT(*) FROM communication.meeting_invitees WHERE meeting_id=?", meetingId));
        assertEquals(4, count("SELECT COUNT(*) FROM notification.notifications WHERE entity_type='PARENT_MEETING' AND entity_id=? AND category='MEETING'", meetingId));
        assertEquals(1, count("SELECT COUNT(*) FROM integration.outbox_events WHERE aggregate_id=? AND event_type='meeting.invited'", meetingId));

        assertTrue(meetings.list(f.schoolId(), f.teacherUser()).stream().anyMatch(row -> row.id().equals(meetingId)));
        assertTrue(meetings.list(f.schoolId(), f.parentA()).stream().anyMatch(row -> row.id().equals(meetingId)));
        assertTrue(meetings.list(f.schoolId(), f.studentUserA()).stream().anyMatch(row -> row.id().equals(meetingId)));
        assertFalse(meetings.list(f.schoolId(), f.unrelatedTeacherUser()).stream().anyMatch(row -> row.id().equals(meetingId)));

        ParentMeetingService.MeetingDetails parent = meetings.details(meetingId, f.parentA());
        assertFalse(parent.manageable());
        assertEquals(1, parent.invitees().size());
        assertTrue(parent.slots().stream().allMatch(slot -> slot.guardianUserId() == null));

        ParentMeetingService.MeetingDetails teacher = meetings.details(meetingId, f.teacherUser());
        assertTrue(teacher.manageable());
        assertEquals(2, teacher.invitees().size());
        assertEquals(2, teacher.slots().size());
    }

    @Test
    void guardianResponseAndSlotBookingAreOptimisticAndConflictSafe() {
        Fixture f = fixture("BOOK");
        UUID meetingId = createClassMeeting(f, false, true);
        ParentMeetingService.MeetingDetails initial = meetings.details(meetingId, f.parentA());
        ParentMeetingService.InviteeView invitee = initial.invitees().getFirst();

        ParentMeetingService.InviteeView accepted = meetings.respond(
                meetingId, f.studentA(), "ACCEPTED", invitee.version(), f.parentA());
        assertEquals("ACCEPTED", accepted.response());
        assertEquals(1L, accepted.version());

        List<ParentMeetingService.SlotView> slots = meetings.details(meetingId, f.parentA()).slots();
        ParentMeetingService.SlotView booked = meetings.bookSlot(
                meetingId, slots.get(0).id(), f.studentA(), slots.get(0).version(), f.parentA());
        assertTrue(booked.mine());
        assertFalse(booked.available());
        assertEquals(1L, booked.version());

        ApiException duplicate = assertThrows(ApiException.class,
                () -> meetings.bookSlot(meetingId, slots.get(1).id(), f.studentA(), slots.get(1).version(), f.parentA()));
        assertEquals("MEETING_SLOT_ALREADY_HELD", duplicate.code());

        ApiException stale = assertThrows(ApiException.class,
                () -> meetings.cancelBooking(meetingId, booked.id(), f.studentA(), 0, f.parentA()));
        assertEquals("VERSION_CONFLICT", stale.code());

        ParentMeetingService.SlotView released = meetings.cancelBooking(
                meetingId, booked.id(), f.studentA(), booked.version(), f.parentA());
        assertTrue(released.available());
        assertFalse(released.mine());
        assertEquals(2L, released.version());

        ParentMeetingService.InviteeView declined = meetings.respond(
                meetingId, f.studentA(), "DECLINED", accepted.version(), f.parentA());
        assertEquals("DECLINED", declined.response());
        assertEquals(0, count("SELECT COUNT(*) FROM communication.meeting_slots WHERE meeting_id=? AND booked_by_guardian_user_id IS NOT NULL", meetingId));
    }

    @Test
    void schoolScopeSupportsNullScopeIdAndUnauthorizedTeachersCannotEscalate() {
        Fixture f = fixture("SCHOOL");
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3);
        UUID schoolMeeting = meetings.create(f.schoolId(), new ParentMeetingService.CreateCommand(
                "SCHOOL", null, "School family forum", "Term plan", null, "Hall A",
                start, start.plusHours(1), true, List.of()), f.adminUser());

        assertNotNull(schoolMeeting);
        assertEquals(2, count("SELECT COUNT(*) FROM communication.meeting_students WHERE meeting_id=?", schoolMeeting));
        assertTrue(meetings.list(f.schoolId(), f.unrelatedTeacherUser()).stream().anyMatch(row -> row.id().equals(schoolMeeting)));

        ApiException teacherSchoolCreate = assertThrows(ApiException.class,
                () -> meetings.create(f.schoolId(), new ParentMeetingService.CreateCommand(
                        "SCHOOL", null, "Not allowed", "Agenda", null, "Hall",
                        start.plusHours(2), start.plusHours(3), false, List.of()), f.teacherUser()));
        assertEquals("FORBIDDEN", teacherSchoolCreate.code());

        ApiException unrelatedStudentCreate = assertThrows(ApiException.class,
                () -> meetings.create(f.schoolId(), new ParentMeetingService.CreateCommand(
                        "STUDENT", f.studentA(), "Not assigned", "Agenda", null, "Room",
                        start.plusHours(2), start.plusHours(3), false, List.of()), f.unrelatedTeacherUser()));
        assertEquals("FORBIDDEN", unrelatedStudentCreate.code());
    }

    @Test
    void attendanceOutcomesAndCompletionRespectVisibilityAndLifecycle() {
        Fixture f = fixture("OUT");
        UUID meetingId = createClassMeeting(f, true, false);
        ParentMeetingService.InviteeView invitee = meetings.details(meetingId, f.parentA()).invitees().getFirst();
        ParentMeetingService.InviteeView accepted = meetings.respond(
                meetingId, f.studentA(), "ACCEPTED", invitee.version(), f.parentA());

        jdbc.update("UPDATE communication.parent_meetings SET starts_at=NOW()-INTERVAL '2 hours',ends_at=NOW()-INTERVAL '1 hour' WHERE id=?", meetingId);

        ParentMeetingService.InviteeView attended = meetings.recordAttendance(
                meetingId, accepted.id(), "PRESENT", accepted.version(), f.teacherUser());
        assertEquals("PRESENT", attended.attendance());

        UUID staffOnly = meetings.addOutcome(meetingId, null, "Internal follow-up", "STAFF_ONLY", f.teacherUser());
        UUID guardian = meetings.addOutcome(meetingId, f.studentA(), "Family follow-up", "GUARDIAN", f.teacherUser());
        UUID shared = meetings.addOutcome(meetingId, f.studentA(), "Shared follow-up", "STUDENT_AND_GUARDIAN", f.teacherUser());
        assertNotNull(staffOnly);

        Set<UUID> parentOutcomes = meetings.details(meetingId, f.parentA()).outcomes().stream()
                .map(ParentMeetingService.OutcomeView::id).collect(Collectors.toSet());
        assertEquals(Set.of(guardian, shared), parentOutcomes);
        Set<UUID> studentOutcomes = meetings.details(meetingId, f.studentUserA()).outcomes().stream()
                .map(ParentMeetingService.OutcomeView::id).collect(Collectors.toSet());
        assertEquals(Set.of(shared), studentOutcomes);
        assertEquals(3, meetings.details(meetingId, f.teacherUser()).outcomes().size());

        ParentMeetingService.MeetingView completed = meetings.complete(meetingId, 0, f.teacherUser());
        assertEquals("COMPLETED", completed.status());
        assertEquals(1L, completed.version());

        ApiException closed = assertThrows(ApiException.class,
                () -> meetings.respond(meetingId, f.studentA(), "DECLINED", attended.version(), f.parentA()));
        assertEquals("INVALID_MEETING_STATE", closed.code());
    }

    @Test
    void remindersAreThrottledAndUseMeetingNotificationCategory() {
        Fixture f = fixture("REM");
        UUID meetingId = createClassMeeting(f, false, false);

        assertEquals(2, meetings.remind(meetingId, f.teacherUser()));
        assertEquals(2, count("SELECT COUNT(*) FROM communication.meeting_invitees WHERE meeting_id=? AND last_reminded_at IS NOT NULL", meetingId));
        assertTrue(count("SELECT COUNT(*) FROM notification.notifications WHERE entity_id=? AND type='meeting.reminder' AND category='MEETING'", meetingId) >= 2);

        ApiException tooSoon = assertThrows(ApiException.class, () -> meetings.remind(meetingId, f.teacherUser()));
        assertEquals("MEETING_REMINDER_TOO_SOON", tooSoon.code());
    }

    private static UUID createClassMeeting(Fixture f, boolean includeStudents, boolean withSlots) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
        List<ParentMeetingService.SlotCommand> slots = withSlots
                ? List.of(
                        new ParentMeetingService.SlotCommand(start.plusMinutes(15), start.plusMinutes(30)),
                        new ParentMeetingService.SlotCommand(start.plusMinutes(45), start.plusMinutes(60)))
                : List.of();
        return meetings.create(f.schoolId(), new ParentMeetingService.CreateCommand(
                "CLASSROOM", f.classroomId(), "Parent meeting", "Learning progress and next steps", "Bring recent work",
                "Room 101", start, start.plusHours(2), includeStudents, slots), f.teacherUser());
    }

    private static Fixture fixture(String prefix) {
        UUID schoolId = UUID.randomUUID();
        jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)",
                schoolId, prefix + "-" + schoolId.toString().substring(0, 6), prefix + " School");

        UUID admin = user(prefix + "-admin", schoolId, "SCHOOL_ADMIN");
        UUID teacherUser = user(prefix + "-teacher", schoolId, "TEACHER");
        UUID unrelatedTeacherUser = user(prefix + "-other", schoolId, "TEACHER");
        UUID parentA = user(prefix + "-parent-a", schoolId, "PARENT");
        UUID parentB = user(prefix + "-parent-b", schoolId, "PARENT");
        UUID studentUserA = user(prefix + "-student-a", schoolId, "STUDENT");
        UUID studentUserB = user(prefix + "-student-b", schoolId, "STUDENT");

        UUID year = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                year, schoolId, "2026-2027-" + prefix, LocalDate.of(2026, 8, 1), LocalDate.of(2027, 6, 30));
        UUID subject = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)",
                subject, schoolId, "MATH-" + prefix, "Mathematics");

        UUID teacher = teacherProfile(schoolId, teacherUser, "Teacher " + prefix);
        teacherProfile(schoolId, unrelatedTeacherUser, "Other " + prefix);
        UUID classroom = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,homeroom_teacher_id,status) VALUES (?,?,?,?,?,?, 'ACTIVE')",
                classroom, schoolId, year, "C-" + prefix, "Class " + prefix, teacher);

        UUID studentA = student(schoolId, studentUserA, "A-" + prefix, "Student A " + prefix);
        UUID studentB = student(schoolId, studentUserB, "B-" + prefix, "Student B " + prefix);
        enroll(schoolId, classroom, studentA);
        enroll(schoolId, classroom, studentB);
        jdbc.update("INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), schoolId, teacher, classroom, subject);

        guardian(schoolId, studentA, parentA, "Parent A " + prefix);
        guardian(schoolId, studentB, parentB, "Parent B " + prefix);

        return new Fixture(schoolId, classroom, studentA, studentB, admin, teacherUser, unrelatedTeacherUser,
                parentA, parentB, studentUserA, studentUserB);
    }

    private static UUID user(String prefix, UUID schoolId, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                id, prefix.toLowerCase() + "-" + id.toString().substring(0, 6) + "@example.com", "unused", prefix);
        jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')",
                id, schoolId, role);
        return id;
    }

    private static UUID teacherProfile(UUID schoolId, UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                id, schoolId, userId, "T-" + id.toString().substring(0, 8), name);
        return id;
    }

    private static UUID student(UUID schoolId, UUID userId, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.students(id,school_id,user_id,student_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                id, schoolId, userId, code, name);
        return id;
    }

    private static void enroll(UUID schoolId, UUID classroomId, UUID studentId) {
        jdbc.update("INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), schoolId, classroomId, studentId, LocalDate.of(2026, 8, 1));
    }

    private static void guardian(UUID schoolId, UUID studentId, UUID userId, String name) {
        UUID guardian = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.guardians(id,school_id,user_id,full_name,status) VALUES (?,?,?,?,'ACTIVE')",
                guardian, schoolId, userId, name);
        jdbc.update("INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact,notifications_enabled) " +
                        "VALUES (?,?,?,'PARENT',TRUE,TRUE)", schoolId, studentId, guardian);
    }

    private static int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private record Fixture(UUID schoolId, UUID classroomId, UUID studentA, UUID studentB,
                           UUID adminUser, UUID teacherUser, UUID unrelatedTeacherUser,
                           UUID parentA, UUID parentB, UUID studentUserA, UUID studentUserB) {}
}
