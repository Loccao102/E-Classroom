package com.eclassroom.core;

import com.eclassroom.core.attendance.LeaveRequestService;
import com.eclassroom.core.audit.AuditService;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class LeaveRequestWorkflowIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_leave_test")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static LeaveRequestService leave;
    static TransactionTemplate tx;

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
        JsonMapper json = JsonMapper.builder().build();
        AccessService access = new AccessService(jdbc);
        OutboxService outbox = new OutboxService(jdbc, json);
        NotificationService notifications = new NotificationService(jdbc, outbox);
        AuditService audit = new AuditService(jdbc, json);
        leave = new LeaveRequestService(jdbc, access, notifications, audit);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void clearCorrelation() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void approveReconcilesOnlyAbsenceAndPersistsAuditNotificationsAndEvents() {
        Fixture fixture = fixture(true);
        String correlationId = "leave-approve-" + UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);

        UUID requestId = tx.execute(status -> leave.create(
                fixture.schoolId(),
                fixture.studentId(),
                fixture.guardianUserId(),
                fixture.attendanceDate(),
                fixture.attendanceDate(),
                "Fever"));

        assertEquals("SUBMITTED", jdbc.queryForObject(
                "SELECT status FROM attendance.leave_requests WHERE id=?", String.class, requestId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT version FROM attendance.leave_requests WHERE id=?", Long.class, requestId));

        Integer submitAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_entries WHERE entity_type='LEAVE_REQUEST' AND entity_id=? " +
                        "AND action='SUBMIT' AND actor_user_id=? AND correlation_id=?",
                Integer.class, requestId, fixture.guardianUserId(), correlationId);
        assertEquals(1, submitAudit);

        Integer reviewerNotification = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification.notifications WHERE recipient_user_id=? AND type='student.leave-request.submitted' AND entity_id=?",
                Integer.class, fixture.teacherUserId(), requestId);
        assertEquals(1, reviewerNotification);

        Map<String, Object> submittedEvent = jdbc.queryForMap(
                "SELECT event_version,correlation_id,payload::text payload FROM integration.outbox_events " +
                        "WHERE event_type='student.leave-request.submitted' AND aggregate_id=?",
                requestId);
        assertEquals(1, ((Number) submittedEvent.get("event_version")).intValue());
        assertEquals(correlationId, submittedEvent.get("correlation_id"));
        assertTrue(String.valueOf(submittedEvent.get("payload")).contains("SUBMITTED"));

        ApiException overlap = assertThrows(ApiException.class, () -> tx.execute(status -> leave.create(
                fixture.schoolId(),
                fixture.studentId(),
                fixture.guardianUserId(),
                fixture.attendanceDate(),
                fixture.attendanceDate().plusDays(1),
                "Still sick")));
        assertEquals("OVERLAPPING_LEAVE_REQUEST", overlap.code());

        tx.executeWithoutResult(status -> leave.review(requestId, true, fixture.teacherUserId()));

        Map<String, Object> reviewed = jdbc.queryForMap(
                "SELECT status,version,reviewed_by FROM attendance.leave_requests WHERE id=?", requestId);
        assertEquals("APPROVED", reviewed.get("status"));
        assertEquals(1L, ((Number) reviewed.get("version")).longValue());
        assertEquals(fixture.teacherUserId(), reviewed.get("reviewed_by"));

        Map<String, Object> attendance = jdbc.queryForMap(
                "SELECT status,version,marked_by FROM attendance.records WHERE id=?", fixture.attendanceRecordId());
        assertEquals("EXCUSED", attendance.get("status"));
        assertEquals(1L, ((Number) attendance.get("version")).longValue());
        assertEquals(fixture.teacherUserId(), attendance.get("marked_by"));

        Integer reviewAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_entries WHERE entity_type='LEAVE_REQUEST' AND entity_id=? " +
                        "AND action='APPROVE' AND actor_user_id=? AND correlation_id=?",
                Integer.class, requestId, fixture.teacherUserId(), correlationId);
        assertEquals(1, reviewAudit);

        Integer attendanceAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_entries WHERE entity_type='ATTENDANCE_RECORD' AND entity_id=? " +
                        "AND action='UPDATE' AND actor_user_id=?",
                Integer.class, fixture.attendanceRecordId(), fixture.teacherUserId());
        assertEquals(1, attendanceAudit);

        Integer guardianNotification = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification.notifications WHERE recipient_user_id=? AND type='student.leave-request.reviewed' AND entity_id=?",
                Integer.class, fixture.guardianUserId(), requestId);
        assertEquals(1, guardianNotification);

        Map<String, Object> reviewedEvent = jdbc.queryForMap(
                "SELECT event_version,correlation_id,payload::text payload FROM integration.outbox_events " +
                        "WHERE event_type='student.leave-request.reviewed' AND aggregate_id=?",
                requestId);
        assertEquals(1, ((Number) reviewedEvent.get("event_version")).intValue());
        assertEquals(correlationId, reviewedEvent.get("correlation_id"));
        assertTrue(String.valueOf(reviewedEvent.get("payload")).contains("APPROVED"));
        assertTrue(String.valueOf(reviewedEvent.get("payload")).contains("reconciledAttendanceRecords"));

        ApiException secondReview = assertThrows(
                ApiException.class,
                () -> tx.executeWithoutResult(status -> leave.review(requestId, false, fixture.teacherUserId())));
        assertEquals("LEAVE_ALREADY_REVIEWED", secondReview.code());
    }

    @Test
    void rejectLeavesAttendanceUnchangedAndUnauthorizedTeacherCannotReview() {
        Fixture fixture = fixture(true);
        UUID unrelatedTeacher = user("unrelated-teacher", "TEACHER", fixture.schoolId());
        UUID requestId = tx.execute(status -> leave.create(
                fixture.schoolId(), fixture.studentId(), fixture.guardianUserId(),
                fixture.attendanceDate(), fixture.attendanceDate(), "Family matter"));

        ApiException forbidden = assertThrows(
                ApiException.class,
                () -> tx.executeWithoutResult(status -> leave.review(requestId, true, unrelatedTeacher)));
        assertEquals("FORBIDDEN", forbidden.code());

        tx.executeWithoutResult(status -> leave.review(requestId, false, fixture.teacherUserId()));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM attendance.leave_requests WHERE id=?", String.class, requestId));
        assertEquals("ABSENT", jdbc.queryForObject(
                "SELECT status FROM attendance.records WHERE id=?", String.class, fixture.attendanceRecordId()));
    }

    @Test
    void concurrentReviewAllowsExactlyOneTerminalTransition() throws Exception {
        Fixture fixture = fixture(false);
        UUID adminUser = user("school-admin", "SCHOOL_ADMIN", fixture.schoolId());
        UUID requestId = tx.execute(status -> leave.create(
                fixture.schoolId(), fixture.studentId(), fixture.guardianUserId(),
                fixture.attendanceDate(), fixture.attendanceDate(), "Medical appointment"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> approve = pool.submit(() -> reviewResult(start, requestId, true, fixture.teacherUserId()));
            Future<String> reject = pool.submit(() -> reviewResult(start, requestId, false, adminUser));
            start.countDown();

            Set<String> results = Set.of(approve.get(), reject.get());
            assertEquals(Set.of("OK", "LEAVE_ALREADY_REVIEWED"), results);
            String finalStatus = jdbc.queryForObject(
                    "SELECT status FROM attendance.leave_requests WHERE id=?", String.class, requestId);
            assertTrue(List.of("APPROVED", "REJECTED").contains(finalStatus));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT version FROM attendance.leave_requests WHERE id=?", Long.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void validationRejectsBlankReasonAndInvalidDateRange() {
        Fixture fixture = fixture(false);
        ApiException blank = assertThrows(ApiException.class, () -> tx.execute(status -> leave.create(
                fixture.schoolId(), fixture.studentId(), fixture.guardianUserId(),
                fixture.attendanceDate(), fixture.attendanceDate(), "   ")));
        assertEquals("INVALID_LEAVE_REASON", blank.code());

        ApiException dates = assertThrows(ApiException.class, () -> tx.execute(status -> leave.create(
                fixture.schoolId(), fixture.studentId(), fixture.guardianUserId(),
                fixture.attendanceDate().plusDays(1), fixture.attendanceDate(), "Reason")));
        assertEquals("INVALID_DATE_RANGE", dates.code());
    }

    private static String reviewResult(CountDownLatch start, UUID requestId, boolean approve, UUID reviewer) throws Exception {
        start.await();
        try {
            tx.executeWithoutResult(status -> leave.review(requestId, approve, reviewer));
            return "OK";
        } catch (ApiException e) {
            return e.code();
        }
    }

    private static Fixture fixture(boolean withAbsentRecord) {
        UUID schoolId = school();
        UUID teacherUserId = user("homeroom", "TEACHER", schoolId);
        UUID guardianUserId = user("guardian", "PARENT", schoolId);
        UUID studentId = student(schoolId);
        guardianLink(schoolId, studentId, guardianUserId);

        UUID yearId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                yearId, schoolId, "2026-2027-" + yearId.toString().substring(0, 8),
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 6, 30));

        UUID teacherId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                teacherId, schoolId, teacherUserId, "GV-" + teacherId.toString().substring(0, 8), "Homeroom Teacher");

        UUID classroomId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,homeroom_teacher_id,status) VALUES (?,?,?,?,?,?,'ACTIVE')",
                classroomId, schoolId, yearId, "10A1-" + classroomId.toString().substring(0, 8), "Class 10A1", teacherId);
        jdbc.update(
                "INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), schoolId, classroomId, studentId, LocalDate.of(2026, 8, 1));

        UUID subjectId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)",
                subjectId, schoolId, "MATH-" + subjectId.toString().substring(0, 8), "Mathematics");
        UUID assignmentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,status) VALUES (?,?,?,?,?,'ACTIVE')",
                assignmentId, schoolId, teacherId, classroomId, subjectId);

        LocalDate date = LocalDate.of(2026, 9, 13);
        UUID recordId = null;
        if (withAbsentRecord) {
            UUID sessionId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO attendance.sessions(id,school_id,classroom_id,teaching_assignment_id,attendance_date,period,created_by) VALUES (?,?,?,?,?,?,?)",
                    sessionId, schoolId, classroomId, assignmentId, date, 1, teacherUserId);
            recordId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO attendance.records(id,school_id,attendance_session_id,student_id,status,note,marked_by) VALUES (?,?,?,?,?,?,?)",
                    recordId, schoolId, sessionId, studentId, "ABSENT", "Initial absence", teacherUserId);
        }
        return new Fixture(schoolId, studentId, teacherUserId, guardianUserId, date, recordId);
    }

    private static UUID school() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO school.schools(id,code,name) VALUES (?,?,?)",
                id, "LEAVE-" + id.toString().substring(0, 8), "Leave Workflow School");
        return id;
    }

    private static UUID user(String prefix, String role, UUID schoolId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                id, prefix + "-" + id + "@example.com", "not-used", prefix + " user");
        jdbc.update(
                "INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')",
                id, schoolId, role);
        return id;
    }

    private static UUID student(UUID schoolId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.students(id,school_id,student_code,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                id, schoolId, "HS-" + id.toString().substring(0, 8), "Student A");
        return id;
    }

    private static void guardianLink(UUID schoolId, UUID studentId, UUID guardianUserId) {
        UUID guardianId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.guardians(id,school_id,user_id,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                guardianId, schoolId, guardianUserId, "Guardian A");
        jdbc.update(
                "INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact,notifications_enabled) " +
                        "VALUES (?,?,?,'PARENT',TRUE,TRUE)",
                schoolId, studentId, guardianId);
    }

    private record Fixture(
            UUID schoolId,
            UUID studentId,
            UUID teacherUserId,
            UUID guardianUserId,
            LocalDate attendanceDate,
            UUID attendanceRecordId) {
    }
}
