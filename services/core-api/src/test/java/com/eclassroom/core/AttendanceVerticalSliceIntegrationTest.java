package com.eclassroom.core;

import com.eclassroom.core.attendance.AttendanceService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.integration.OutboxService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.ApiException;
import com.eclassroom.core.shared.api.CorrelationIdFilter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.MDC;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class AttendanceVerticalSliceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_attendance_test")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static AttendanceService attendance;

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
        NotificationService notifications = new NotificationService(jdbc, outbox);
        attendance = new AttendanceService(jdbc, access, notifications);
    }

    @AfterEach
    void clearCorrelation() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void absentAttendanceCommitsAuditGuardianNotificationAndOutboxAtomically() {
        UUID schoolId = school();
        UUID teacherUserId = user("teacher", "TEACHER", schoolId);
        UUID guardianUserId = user("guardian", "PARENT", schoolId);
        UUID studentId = student(schoolId);
        UUID assignmentId = assignment(schoolId, teacherUserId, studentId);
        guardianLink(schoolId, studentId, guardianUserId);

        String correlationId = "attendance-test-" + UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        LocalDate date = LocalDate.of(2026, 9, 13);

        UUID sessionId = attendance.create(schoolId, assignmentId, date, 1, teacherUserId);
        long nextVersion = attendance.save(
                sessionId,
                0,
                List.of(new AttendanceService.RecordInput(studentId, "ABSENT", "Unexcused absence")),
                teacherUserId);

        assertEquals(1L, nextVersion);
        assertEquals("ABSENT", jdbc.queryForObject(
                "SELECT status FROM attendance.records WHERE attendance_session_id=? AND student_id=?",
                String.class,
                sessionId,
                studentId));

        Integer auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_entries WHERE school_id=? AND entity_type='ATTENDANCE_RECORD' AND action='INSERT' AND actor_user_id=?",
                Integer.class,
                schoolId,
                teacherUserId);
        assertEquals(1, auditRows);

        Map<String, Object> notification = jdbc.queryForMap(
                "SELECT recipient_user_id,type,entity_type,entity_id FROM notification.notifications WHERE school_id=? AND recipient_user_id=?",
                schoolId,
                guardianUserId);
        assertEquals(guardianUserId, notification.get("recipient_user_id"));
        assertEquals("student.attendance.changed", notification.get("type"));
        assertEquals("ATTENDANCE_SESSION", notification.get("entity_type"));
        assertEquals(sessionId, notification.get("entity_id"));

        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT event_type,event_version,correlation_id,payload::text payload,published_at FROM integration.outbox_events WHERE school_id=? AND event_type='student.attendance.changed'",
                schoolId);
        assertEquals("student.attendance.changed", outbox.get("event_type"));
        assertEquals(1, ((Number) outbox.get("event_version")).intValue());
        assertEquals(correlationId, outbox.get("correlation_id"));
        assertTrue(String.valueOf(outbox.get("payload")).contains(guardianUserId.toString()));
        assertTrue(String.valueOf(outbox.get("payload")).contains(correlationId));
        assertEquals(null, outbox.get("published_at"));

        ApiException conflict = assertThrows(
                ApiException.class,
                () -> attendance.save(
                        sessionId,
                        0,
                        List.of(new AttendanceService.RecordInput(studentId, "PRESENT", null)),
                        teacherUserId));
        assertEquals("VERSION_CONFLICT", conflict.code());
    }

    private static UUID school() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO school.schools(id,code,name) VALUES (?,?,?)",
                id,
                "SLICE-" + id.toString().substring(0, 8),
                "Vertical Slice School");
        return id;
    }

    private static UUID user(String prefix, String role, UUID schoolId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                id,
                prefix + "-" + id + "@example.com",
                "not-used",
                prefix + " user");
        jdbc.update(
                "INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')",
                id,
                schoolId,
                role);
        return id;
    }

    private static UUID student(UUID schoolId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.students(id,school_id,student_code,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                id,
                schoolId,
                "HS-" + id.toString().substring(0, 8),
                "Student A");
        return id;
    }

    private static UUID assignment(UUID schoolId, UUID teacherUserId, UUID studentId) {
        UUID yearId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                yearId,
                schoolId,
                "2026-2027-" + yearId.toString().substring(0, 8),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 6, 30));

        UUID teacherId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                teacherId,
                schoolId,
                teacherUserId,
                "GV-" + teacherId.toString().substring(0, 8),
                "Teacher A");

        UUID classroomId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                classroomId,
                schoolId,
                yearId,
                "10A1-" + classroomId.toString().substring(0, 8),
                "Class 10A1");

        UUID subjectId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)",
                subjectId,
                schoolId,
                "MATH-" + subjectId.toString().substring(0, 8),
                "Mathematics");

        UUID assignmentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,status) VALUES (?,?,?,?,?,'ACTIVE')",
                assignmentId,
                schoolId,
                teacherId,
                classroomId,
                subjectId);

        jdbc.update(
                "INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(),
                schoolId,
                classroomId,
                studentId,
                LocalDate.of(2026, 8, 1));
        return assignmentId;
    }

    private static void guardianLink(UUID schoolId, UUID studentId, UUID guardianUserId) {
        UUID guardianId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.guardians(id,school_id,user_id,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                guardianId,
                schoolId,
                guardianUserId,
                "Guardian A");
        jdbc.update(
                "INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact,notifications_enabled) VALUES (?,?,?,'PARENT',TRUE,TRUE)",
                schoolId,
                studentId,
                guardianId);
    }
}
