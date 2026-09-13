package com.eclassroom.core;

import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.grading.GradingService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class GradingWorkflowIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_grading_test")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static GradingService grading;
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
        grading = new GradingService(jdbc, access, notifications, audit, outbox);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void clearCorrelation() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void bulkSaveSkipsNoOpsAndRevisionHistoryUsesKeysetCursor() {
        Fixture fixture = fixture();
        UUID assessmentId = createAssessment(fixture, "Quiz 1");
        String correlationId = "grading-batch-" + UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);

        tx.executeWithoutResult(status -> grading.saveScores(
                assessmentId,
                List.of(
                        new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("8.50")),
                        new GradingService.ScoreInput(fixture.studentB(), new BigDecimal("7.00"))),
                "Initial entry",
                fixture.teacherUser()));

        assertEquals(2, count("grading.student_scores", "assessment_id", assessmentId));
        assertEquals(2, count("grading.score_revisions", "school_id", fixture.schoolId()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM grading.score_revisions WHERE school_id=? AND correlation_id=?",
                Integer.class, fixture.schoolId(), correlationId));

        tx.executeWithoutResult(status -> grading.saveScores(
                assessmentId,
                List.of(
                        new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("8.50")),
                        new GradingService.ScoreInput(fixture.studentB(), new BigDecimal("7.00"))),
                "No-op retry",
                fixture.teacherUser()));
        assertEquals(2, count("grading.score_revisions", "school_id", fixture.schoolId()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT version FROM grading.student_scores WHERE assessment_id=? AND student_id=?",
                Long.class, assessmentId, fixture.studentA()));

        tx.executeWithoutResult(status -> grading.saveScores(
                assessmentId,
                List.of(new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("9.00"))),
                "Correction",
                fixture.teacherUser()));
        assertEquals(3, count("grading.score_revisions", "school_id", fixture.schoolId()));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM grading.student_scores WHERE assessment_id=? AND student_id=?",
                Long.class, assessmentId, fixture.studentA()));

        GradingService.RevisionPage first = grading.revisions(
                assessmentId, 2, null, null, fixture.teacherUser());
        assertEquals(2, first.items().size());
        assertNotNull(first.nextCursor());

        GradingService.RevisionPage second = grading.revisions(
                assessmentId,
                2,
                first.nextCursor().beforeCreatedAt(),
                first.nextCursor().beforeId(),
                fixture.teacherUser());
        assertEquals(1, second.items().size());

        Set<UUID> ids = new HashSet<>();
        first.items().forEach(item -> ids.add(item.id()));
        second.items().forEach(item -> ids.add(item.id()));
        assertEquals(3, ids.size());
    }

    @Test
    void draftScoresAreHiddenUntilSubmitAndSubmissionIsIdempotent() {
        Fixture fixture = fixture();
        UUID assessmentId = createAssessment(fixture, "Midterm");
        tx.executeWithoutResult(status -> grading.saveScores(
                assessmentId,
                List.of(new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("8.25"))),
                "Teacher entry",
                fixture.teacherUser()));

        assertTrue(grading.scores(fixture.schoolId(), fixture.studentA(), fixture.guardianUser()).isEmpty());

        String correlationId = "grading-submit-" + UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        long submittedVersion = tx.execute(status -> grading.submit(assessmentId, 0L, fixture.teacherUser()));
        assertEquals(1L, submittedVersion);
        assertEquals("SUBMITTED", jdbc.queryForObject(
                "SELECT status FROM grading.assessments WHERE id=?", String.class, assessmentId));
        assertEquals("SUBMITTED", jdbc.queryForObject(
                "SELECT status FROM grading.student_scores WHERE assessment_id=? AND student_id=?",
                String.class, assessmentId, fixture.studentA()));
        assertEquals(1, grading.scores(fixture.schoolId(), fixture.studentA(), fixture.guardianUser()).size());

        Integer guardianNotifications = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification.notifications WHERE recipient_user_id=? " +
                        "AND type='student.score.published' AND entity_id=?",
                Integer.class, fixture.guardianUser(), assessmentId);
        assertEquals(1, guardianNotifications);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration.outbox_events WHERE event_type='assessment.submitted' AND aggregate_id=?",
                Integer.class, assessmentId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration.outbox_events WHERE event_type='student.score.published' AND aggregate_id=?",
                Integer.class, assessmentId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_entries WHERE entity_type='ASSESSMENT' AND entity_id=? " +
                        "AND action='SUBMIT' AND correlation_id=?",
                Integer.class, assessmentId, correlationId));

        long retryVersion = tx.execute(status -> grading.submit(assessmentId, 0L, fixture.teacherUser()));
        assertEquals(1L, retryVersion);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification.notifications WHERE recipient_user_id=? " +
                        "AND type='student.score.published' AND entity_id=?",
                Integer.class, fixture.guardianUser(), assessmentId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration.outbox_events WHERE event_type='assessment.submitted' AND aggregate_id=?",
                Integer.class, assessmentId));

        ApiException editAfterSubmit = assertThrows(
                ApiException.class,
                () -> tx.executeWithoutResult(status -> grading.saveScores(
                        assessmentId,
                        List.of(new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("9.00"))),
                        "Late edit",
                        fixture.teacherUser())));
        assertEquals("ASSESSMENT_NOT_EDITABLE", editAfterSubmit.code());

        long lockedVersion = tx.execute(status -> grading.lock(assessmentId, 1L, fixture.adminUser()));
        assertEquals(2L, lockedVersion);
        assertEquals("LOCKED", jdbc.queryForObject(
                "SELECT status FROM grading.assessments WHERE id=?", String.class, assessmentId));
        assertEquals("LOCKED", jdbc.queryForObject(
                "SELECT status FROM grading.student_scores WHERE assessment_id=? AND student_id=?",
                String.class, assessmentId, fixture.studentA()));
        long retryLockedVersion = tx.execute(status -> grading.lock(assessmentId, 1L, fixture.adminUser()));
        assertEquals(2L, retryLockedVersion);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration.outbox_events WHERE event_type='assessment.locked' AND aggregate_id=?",
                Integer.class, assessmentId));
    }

    @Test
    void invalidBatchVersionAndAuthorizationFailuresDoNotMutateScores() {
        Fixture fixture = fixture();
        UUID assessmentId = createAssessment(fixture, "Quiz validation");

        ApiException duplicate = assertThrows(
                ApiException.class,
                () -> tx.executeWithoutResult(status -> grading.saveScores(
                        assessmentId,
                        List.of(
                                new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("5")),
                                new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("6"))),
                        "Duplicate",
                        fixture.teacherUser())));
        assertEquals("DUPLICATE_STUDENT_SCORE", duplicate.code());
        assertEquals(0, count("grading.student_scores", "assessment_id", assessmentId));

        ApiException unassigned = assertThrows(
                ApiException.class,
                () -> tx.executeWithoutResult(status -> grading.saveScores(
                        assessmentId,
                        List.of(new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("5"))),
                        "Unauthorized",
                        fixture.unassignedTeacherUser())));
        assertEquals("FORBIDDEN", unassigned.code());

        ApiException crossTenant = assertThrows(
                ApiException.class,
                () -> tx.executeWithoutResult(status -> grading.saveScores(
                        assessmentId,
                        List.of(new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("5"))),
                        "Cross tenant",
                        fixture.otherSchoolTeacherUser())));
        assertEquals("FORBIDDEN", crossTenant.code());

        tx.executeWithoutResult(status -> grading.saveScores(
                assessmentId,
                List.of(new GradingService.ScoreInput(fixture.studentA(), new BigDecimal("5"))),
                "Valid",
                fixture.teacherUser()));
        ApiException versionConflict = assertThrows(
                ApiException.class,
                () -> tx.execute(status -> grading.submit(assessmentId, 99L, fixture.teacherUser())));
        assertEquals("VERSION_CONFLICT", versionConflict.code());
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM grading.assessments WHERE id=?", String.class, assessmentId));

        ApiException lockDraft = assertThrows(
                ApiException.class,
                () -> tx.execute(status -> grading.lock(assessmentId, 0L, fixture.adminUser())));
        assertEquals("ASSESSMENT_NOT_SUBMITTED", lockDraft.code());
    }

    @Test
    void assessmentMetadataMustMatchSemesterAndPositiveRanges() {
        Fixture fixture = fixture();
        ApiException max = assertThrows(
                ApiException.class,
                () -> tx.execute(status -> grading.create(
                        fixture.schoolId(), fixture.assignmentId(), fixture.semesterId(),
                        "Bad", "QUIZ", BigDecimal.ZERO, BigDecimal.ONE,
                        fixture.assessmentDate(), fixture.teacherUser())));
        assertEquals("INVALID_MAX_SCORE", max.code());

        UUID otherSemester = semester(fixture.otherSchoolId());
        ApiException semester = assertThrows(
                ApiException.class,
                () -> tx.execute(status -> grading.create(
                        fixture.schoolId(), fixture.assignmentId(), otherSemester,
                        "Cross school semester", "QUIZ", BigDecimal.TEN, BigDecimal.ONE,
                        fixture.assessmentDate(), fixture.teacherUser())));
        assertEquals("INVALID_SEMESTER", semester.code());
    }

    private static UUID createAssessment(Fixture fixture, String title) {
        return tx.execute(status -> grading.create(
                fixture.schoolId(),
                fixture.assignmentId(),
                fixture.semesterId(),
                title,
                "QUIZ",
                BigDecimal.TEN,
                BigDecimal.ONE,
                fixture.assessmentDate(),
                fixture.teacherUser()));
    }

    private static Fixture fixture() {
        UUID schoolId = school("GRADING");
        UUID otherSchoolId = school("OTHER");
        UUID teacherUser = user("teacher", "TEACHER", schoolId);
        UUID adminUser = user("admin", "SCHOOL_ADMIN", schoolId);
        UUID guardianUser = user("guardian", "PARENT", schoolId);
        UUID unassignedTeacher = user("unassigned", "TEACHER", schoolId);
        UUID otherSchoolTeacher = user("outsider", "TEACHER", otherSchoolId);

        UUID yearId = academicYear(schoolId);
        UUID semesterId = semester(schoolId, yearId);
        UUID teacherId = teacher(schoolId, teacherUser, "Assigned Teacher");
        teacher(schoolId, unassignedTeacher, "Unassigned Teacher");
        UUID classroomId = classroom(schoolId, yearId, teacherId);
        UUID studentA = student(schoolId, "Student A");
        UUID studentB = student(schoolId, "Student B");
        enroll(schoolId, classroomId, studentA);
        enroll(schoolId, classroomId, studentB);
        UUID guardianId = guardian(schoolId, guardianUser, "Guardian A");
        guardianLink(schoolId, studentA, guardianId);
        guardianLink(schoolId, studentB, guardianId);
        UUID subjectId = subject(schoolId);
        UUID assignmentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,semester_id,status) " +
                        "VALUES (?,?,?,?,?,?,'ACTIVE')",
                assignmentId, schoolId, teacherId, classroomId, subjectId, semesterId);

        return new Fixture(
                schoolId,
                otherSchoolId,
                semesterId,
                assignmentId,
                teacherUser,
                adminUser,
                guardianUser,
                unassignedTeacher,
                otherSchoolTeacher,
                studentA,
                studentB,
                LocalDate.of(2026, 9, 15));
    }

    private static UUID school(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO school.schools(id,code,name) VALUES (?,?,?)",
                id, prefix + "-" + id.toString().substring(0, 8), prefix + " School");
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

    private static UUID academicYear(UUID schoolId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                id, schoolId, "2026-2027-" + id.toString().substring(0, 8),
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 6, 30));
        return id;
    }

    private static UUID semester(UUID schoolId) {
        return semester(schoolId, academicYear(schoolId));
    }

    private static UUID semester(UUID schoolId, UUID yearId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.semesters(id,school_id,academic_year_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,?,'ACTIVE')",
                id, schoolId, yearId, "Semester-" + id.toString().substring(0, 8),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31));
        return id;
    }

    private static UUID teacher(UUID schoolId, UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                id, schoolId, userId, "GV-" + id.toString().substring(0, 8), name);
        return id;
    }

    private static UUID classroom(UUID schoolId, UUID yearId, UUID homeroomTeacherId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,homeroom_teacher_id,status) VALUES (?,?,?,?,?,?,'ACTIVE')",
                id, schoolId, yearId, "10A-" + id.toString().substring(0, 8), "Class 10A", homeroomTeacherId);
        return id;
    }

    private static UUID student(UUID schoolId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.students(id,school_id,student_code,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                id, schoolId, "HS-" + id.toString().substring(0, 8), name);
        return id;
    }

    private static void enroll(UUID schoolId, UUID classroomId, UUID studentId) {
        jdbc.update(
                "INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), schoolId, classroomId, studentId, LocalDate.of(2026, 8, 1));
    }

    private static UUID guardian(UUID schoolId, UUID guardianUser, String name) {
        UUID guardianId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.guardians(id,school_id,user_id,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                guardianId, schoolId, guardianUser, name);
        return guardianId;
    }

    private static void guardianLink(UUID schoolId, UUID studentId, UUID guardianId) {
        jdbc.update(
                "INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact,notifications_enabled) " +
                        "VALUES (?,?,?,'PARENT',TRUE,TRUE)",
                schoolId, studentId, guardianId);
    }

    private static UUID subject(UUID schoolId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)",
                id, schoolId, "MATH-" + id.toString().substring(0, 8), "Mathematics");
        return id;
    }

    private static int count(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class, value);
    }

    private record Fixture(
            UUID schoolId,
            UUID otherSchoolId,
            UUID semesterId,
            UUID assignmentId,
            UUID teacherUser,
            UUID adminUser,
            UUID guardianUser,
            UUID unassignedTeacherUser,
            UUID otherSchoolTeacherUser,
            UUID studentA,
            UUID studentB,
            LocalDate assessmentDate) {}
}
