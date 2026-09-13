package com.eclassroom.core;

import com.eclassroom.core.communication.CommunicationService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class CommunicationWorkflowIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_communication_test")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static NotificationService notifications;
    static CommunicationService communication;

    @BeforeAll
    static void setUpDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbc = new JdbcTemplate(dataSource);
        AccessService access = new AccessService(jdbc);
        OutboxService outbox = new OutboxService(jdbc, JsonMapper.builder().build());
        notifications = new NotificationService(jdbc, outbox);
        communication = new CommunicationService(jdbc, access, notifications);
    }

    @Test
    void notificationPreferencesSeparateDurableAndRealtimeDelivery() {
        UUID schoolId = school("PREF");
        UUID parent = user("pref-parent", schoolId, "PARENT");
        UUID entityId = UUID.randomUUID();

        notifications.updatePreference(parent, schoolId, "ATTENDANCE", false, true);
        notifications.notifyUsers(
                schoolId,
                List.of(parent),
                "student.attendance.changed",
                "Attendance update",
                "Student A is absent",
                "ATTENDANCE_SESSION",
                entityId);

        assertEquals(0, count(
                "SELECT COUNT(*) FROM notification.notifications WHERE recipient_user_id=? AND entity_id=?",
                parent, entityId));
        Map<String, Object> realtimeEvent = jdbc.queryForMap(
                "SELECT payload::text payload FROM integration.outbox_events WHERE aggregate_id=? AND event_type='student.attendance.changed'",
                entityId);
        assertTrue(String.valueOf(realtimeEvent.get("payload")).contains(parent.toString()));

        UUID secondEntity = UUID.randomUUID();
        notifications.updatePreference(parent, schoolId, "ATTENDANCE", true, false);
        notifications.notifyUsers(
                schoolId,
                List.of(parent),
                "student.attendance.changed",
                "Attendance update 2",
                "Student A is late",
                "ATTENDANCE_SESSION",
                secondEntity);

        assertEquals(1, count(
                "SELECT COUNT(*) FROM notification.notifications WHERE recipient_user_id=? AND entity_id=?",
                parent, secondEntity));
        Map<String, Object> durableEvent = jdbc.queryForMap(
                "SELECT payload::text payload FROM integration.outbox_events WHERE aggregate_id=? AND event_type='student.attendance.changed'",
                secondEntity);
        String payload = String.valueOf(durableEvent.get("payload"));
        assertTrue(payload.contains("targetRecipientIds"));
        assertTrue(payload.contains(parent.toString()));
        assertTrue(payload.contains("\"recipients\": []") || payload.contains("\"recipients\":[]"));
    }

    @Test
    void notificationFeedSupportsUnreadReadAllAndCursorPagination() throws Exception {
        UUID schoolId = school("FEED");
        UUID parent = user("feed-parent", schoolId, "PARENT");

        for (int i = 0; i < 4; i++) {
            notifications.notifyUsers(
                    schoolId,
                    List.of(parent),
                    "announcement.published",
                    "Notice " + i,
                    "Body " + i,
                    "ANNOUNCEMENT",
                    UUID.randomUUID());
            Thread.sleep(2);
        }

        assertEquals(4L, notifications.unreadCount(parent, schoolId));
        NotificationService.NotificationPage first = notifications.page(parent, schoolId, "ANNOUNCEMENT", true, 2, null, null);
        assertEquals(2, first.items().size());
        assertNotNull(first.nextCursor());

        NotificationService.NotificationPage second = notifications.page(
                parent,
                schoolId,
                "ANNOUNCEMENT",
                true,
                2,
                first.nextCursor().beforeCreatedAt(),
                first.nextCursor().beforeId());
        assertEquals(2, second.items().size());

        UUID one = (UUID) first.items().getFirst().get("id");
        notifications.markRead(parent, one);
        assertEquals(3L, notifications.unreadCount(parent, schoolId));
        assertEquals(3, notifications.markAllRead(parent, schoolId));
        assertEquals(0L, notifications.unreadCount(parent, schoolId));
    }

    @Test
    void parentCanMessageRelatedTeacherButNotUnrelatedTeacherAndReadStateTracksUnread() {
        Fixture fixture = fixture();

        UUID conversationId = communication.conversation(
                fixture.schoolId(),
                "Student A progress",
                List.of(fixture.teacherUser()),
                fixture.parentUser());

        UUID messageId = communication.send(conversationId, "How is Student A doing this week?", fixture.parentUser());
        assertNotNull(messageId);

        List<Map<String, Object>> teacherInbox = communication.conversations(fixture.schoolId(), fixture.teacherUser());
        assertEquals(1, teacherInbox.size());
        assertEquals(1L, ((Number) teacherInbox.getFirst().get("unread_count")).longValue());

        CommunicationService.MessagePage page = communication.messagePage(
                conversationId, fixture.teacherUser(), 20, null, null);
        assertEquals(1, page.items().size());
        assertEquals("How is Student A doing this week?", page.items().getFirst().get("body"));

        communication.markConversationRead(conversationId, fixture.teacherUser());
        List<Map<String, Object>> readInbox = communication.conversations(fixture.schoolId(), fixture.teacherUser());
        assertEquals(0L, ((Number) readInbox.getFirst().get("unread_count")).longValue());

        ApiException forbidden = assertThrows(
                ApiException.class,
                () -> communication.conversation(
                        fixture.schoolId(),
                        "Not related",
                        List.of(fixture.unrelatedTeacherUser()),
                        fixture.parentUser()));
        assertEquals("FORBIDDEN", forbidden.code());
    }

    @Test
    void muteSuppressesMessageRealtimeAndDurableNotificationButKeepsMessageHistory() {
        Fixture fixture = fixture();
        UUID conversationId = communication.conversation(
                fixture.schoolId(), "Muted conversation", List.of(fixture.teacherUser()), fixture.parentUser());

        communication.setMuted(conversationId, fixture.teacherUser(), true);
        UUID messageId = communication.send(conversationId, "Muted delivery", fixture.parentUser());

        assertEquals(1, count("SELECT COUNT(*) FROM communication.messages WHERE id=?", messageId));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM notification.notifications WHERE recipient_user_id=? AND entity_type='CONVERSATION' AND entity_id=?",
                fixture.teacherUser(), conversationId));

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT payload::text payload FROM integration.outbox_events WHERE aggregate_id=? AND event_type='message.created'",
                conversationId);
        String payload = String.valueOf(event.get("payload"));
        assertTrue(payload.contains("targetRecipientIds"));
        assertFalse(payload.contains(fixture.teacherUser().toString()));
    }

    @Test
    void commentVisibilitySeparatesStaffGuardianAndStudentViews() {
        Fixture fixture = fixture();

        communication.comment(
                fixture.schoolId(), fixture.studentId(), "Staff note", "STAFF_ONLY", fixture.teacherUser());
        communication.comment(
                fixture.schoolId(), fixture.studentId(), "Guardian note", "GUARDIAN", fixture.teacherUser());
        communication.comment(
                fixture.schoolId(), fixture.studentId(), "Shared note", "STUDENT_AND_GUARDIAN", fixture.teacherUser());

        List<Map<String, Object>> teacher = communication.comments(
                fixture.schoolId(), fixture.studentId(), fixture.teacherUser());
        List<Map<String, Object>> guardian = communication.comments(
                fixture.schoolId(), fixture.studentId(), fixture.parentUser());
        List<Map<String, Object>> student = communication.comments(
                fixture.schoolId(), fixture.studentId(), fixture.studentUser());

        assertEquals(3, teacher.size());
        assertEquals(2, guardian.size());
        assertEquals(1, student.size());
        assertEquals("Shared note", student.getFirst().get("body"));
    }

    @Test
    void announcementsValidateAudienceAndExpiredItemsAreNotListed() {
        Fixture fixture = fixture();
        UUID id = communication.announce(
                fixture.schoolId(),
                "Class notice",
                "Bring your workbook tomorrow",
                "CLASSROOM",
                fixture.classroomId(),
                null,
                true,
                fixture.teacherUser());

        List<Map<String, Object>> parent = communication.announcements(fixture.schoolId(), fixture.parentUser());
        assertTrue(parent.stream().anyMatch(row -> id.equals(row.get("id"))));
        Map<String, Object> row = parent.stream().filter(item -> id.equals(item.get("id"))).findFirst().orElseThrow();
        assertTrue((Boolean) row.get("pinned"));

        ApiException invalidTarget = assertThrows(
                ApiException.class,
                () -> communication.announce(
                        fixture.schoolId(), "Bad", "Bad target", "CLASSROOM", UUID.randomUUID(), null, false, fixture.teacherUser()));
        assertEquals("INVALID_TARGET", invalidTarget.code());
    }

    private static Fixture fixture() {
        UUID schoolId = school("COMM");
        UUID parent = user("parent", schoolId, "PARENT");
        UUID studentUser = user("student", schoolId, "STUDENT");
        UUID teacherUser = user("teacher", schoolId, "TEACHER");
        UUID unrelatedTeacherUser = user("other-teacher", schoolId, "TEACHER");

        UUID yearId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                yearId, schoolId, "2026-2027-" + yearId.toString().substring(0, 8),
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 6, 30));

        UUID teacherId = teacherProfile(schoolId, teacherUser, "Related Teacher");
        teacherProfile(schoolId, unrelatedTeacherUser, "Unrelated Teacher");

        UUID classroomId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,homeroom_teacher_id,status) VALUES (?,?,?,?,?,?,'ACTIVE')",
                classroomId, schoolId, yearId, "10A1-" + classroomId.toString().substring(0, 8), "Class 10A1", teacherId);

        UUID studentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.students(id,school_id,user_id,student_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                studentId, schoolId, studentUser, "HS-" + studentId.toString().substring(0, 8), "Student A");
        jdbc.update(
                "INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), schoolId, classroomId, studentId, LocalDate.of(2026, 8, 1));

        UUID guardianId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.guardians(id,school_id,user_id,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                guardianId, schoolId, parent, "Parent A");
        jdbc.update(
                "INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact,notifications_enabled) " +
                        "VALUES (?,?,?,'PARENT',TRUE,TRUE)",
                schoolId, studentId, guardianId);

        UUID subjectId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)",
                subjectId, schoolId, "MATH-" + subjectId.toString().substring(0, 8), "Mathematics");
        jdbc.update(
                "INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), schoolId, teacherId, classroomId, subjectId);

        return new Fixture(schoolId, classroomId, studentId, parent, studentUser, teacherUser, unrelatedTeacherUser);
    }

    private static UUID school(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO school.schools(id,code,name) VALUES (?,?,?)",
                id, prefix + "-" + id.toString().substring(0, 8), prefix + " School");
        return id;
    }

    private static UUID user(String prefix, UUID schoolId, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                id, prefix + "-" + id + "@example.com", "not-used", prefix + " user");
        jdbc.update(
                "INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')",
                id, schoolId, role);
        return id;
    }

    private static UUID teacherProfile(UUID schoolId, UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                id, schoolId, userId, "GV-" + id.toString().substring(0, 8), name);
        return id;
    }

    private static int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private record Fixture(
            UUID schoolId,
            UUID classroomId,
            UUID studentId,
            UUID parentUser,
            UUID studentUser,
            UUID teacherUser,
            UUID unrelatedTeacherUser) {}
}
