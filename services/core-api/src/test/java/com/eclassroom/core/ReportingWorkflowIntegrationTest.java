package com.eclassroom.core;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.reporting.ReportingService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class ReportingWorkflowIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_reporting_test")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static ReportingService reporting;
    static Fixture fixture;

    @BeforeAll
    static void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        AccessService access = new AccessService(jdbc);
        reporting = new ReportingService(jdbc, access);
        fixture = seed();
    }

    @Test
    void schoolDashboardNormalizesMixedAssessmentScalesAndCalculatesAttendance() {
        Map<String, Object> dashboard = reporting.schoolDashboard(
                fixture.schoolId(), fixture.adminUser(), null, null, fixture.yearId(), null);

        Map<?, ?> attendance = (Map<?, ?>) dashboard.get("attendance");
        Map<?, ?> scores = (Map<?, ?>) dashboard.get("scores");
        assertEquals(5L, ((Number) attendance.get("total")).longValue());
        assertEquals(80.0, ((Number) attendance.get("attendanceRate")).doubleValue(), 0.001);
        assertEquals(20.0, ((Number) attendance.get("absenceRate")).doubleValue(), 0.001);
        assertEquals(6.50, ((Number) scores.get("averageScore")).doubleValue(), 0.001,
                "10/20 must normalize to 5/10 before averaging with 8/10");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> distribution = (List<Map<String, Object>>) dashboard.get("scoreDistribution");
        assertEquals(5, distribution.size());
        assertEquals(2L, distribution.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum());
    }

    @Test
    void teacherRiskListIsRestrictedToAssignedClassrooms() {
        List<Map<String, Object>> teacherOne = reporting.risks(
                fixture.schoolId(), fixture.teacherOneUser(), null, null, fixture.yearId(), null);
        List<Map<String, Object>> teacherTwo = reporting.risks(
                fixture.schoolId(), fixture.teacherTwoUser(), null, null, fixture.yearId(), null);

        assertEquals(1, teacherOne.size());
        assertEquals(fixture.studentA(), teacherOne.getFirst().get("studentId"));
        assertFalse(teacherOne.stream().anyMatch(row -> fixture.studentB().equals(row.get("studentId"))));

        assertEquals(1, teacherTwo.size());
        assertEquals(fixture.studentB(), teacherTwo.getFirst().get("studentId"));
    }

    @Test
    void riskResponseIsExplainableAndStudentWeightedAverageUsesNormalizedScores() {
        Map<String, Object> report = reporting.student(
                fixture.schoolId(), fixture.studentA(), fixture.adminUser(), null, null, fixture.yearId(), null);
        Map<?, ?> risk = (Map<?, ?>) report.get("risk");

        assertEquals("HIGH", risk.get("riskLevel"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> factors = (List<Map<String, Object>>) risk.get("factors");
        assertTrue(factors.stream().anyMatch(row -> "ABSENCE".equals(row.get("code"))));
        Map<String, Object> absence = factors.stream().filter(row -> "ABSENCE".equals(row.get("code"))).findFirst().orElseThrow();
        assertEquals(20.0, ((Number) absence.get("value")).doubleValue(), 0.001);
        assertEquals(20.0, ((Number) absence.get("threshold")).doubleValue(), 0.001);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subjectAverages = (List<Map<String, Object>>) report.get("subjectAverages");
        assertEquals(1, subjectAverages.size());
        assertEquals(6.50, ((Number) subjectAverages.getFirst().get("weightedAverage")).doubleValue(), 0.001);
    }

    @Test
    void reportRangeRejectsCrossSchoolAcademicYear() {
        UUID otherSchool = school("OTHER");
        UUID otherYear = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                otherYear, otherSchool, "2026-2027", LocalDate.of(2026, 8, 1), LocalDate.of(2027, 6, 30));

        boolean rejected = false;
        try {
            reporting.schoolDashboard(fixture.schoolId(), fixture.adminUser(), null, null, otherYear, null);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    private static Fixture seed() {
        UUID school = school("REPORT");
        UUID admin = user("admin", school, "SCHOOL_ADMIN");
        UUID teacherOneUser = user("teacher-one", school, "TEACHER");
        UUID teacherTwoUser = user("teacher-two", school, "TEACHER");

        UUID year = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                year, school, "2026-2027", LocalDate.of(2026, 8, 1), LocalDate.of(2027, 6, 30));
        UUID semester = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.semesters(id,school_id,academic_year_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,?,'ACTIVE')",
                semester, school, year, "Học kỳ 1", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31));
        UUID subject = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)", subject, school, "MATH", "Toán");

        UUID teacherOne = teacher(school, teacherOneUser, "GV01");
        UUID teacherTwo = teacher(school, teacherTwoUser, "GV02");
        UUID classA = classroom(school, year, teacherOne, "10A1");
        UUID classB = classroom(school, year, teacherTwo, "10A2");
        UUID studentA = student(school, "HS01", "Nguyễn An");
        UUID studentB = student(school, "HS02", "Trần Bình");
        enroll(school, classA, studentA);
        enroll(school, classB, studentB);
        UUID assignmentA = assignment(school, teacherOne, classA, subject, semester);
        assignment(school, teacherTwo, classB, subject, semester);

        List<String> statuses = List.of("ABSENT", "PRESENT", "PRESENT", "PRESENT", "PRESENT");
        for (int i = 0; i < statuses.size(); i++) {
            UUID session = UUID.randomUUID();
            jdbc.update("INSERT INTO attendance.sessions(id,school_id,classroom_id,teaching_assignment_id,attendance_date,period,status,created_by) VALUES (?,?,?,?,?,1,'SUBMITTED',?)",
                    session, school, classA, assignmentA, LocalDate.of(2026, 9, 1).plusDays(i), teacherOneUser);
            jdbc.update("INSERT INTO attendance.records(id,school_id,attendance_session_id,student_id,status,marked_by) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID(), school, session, studentA, statuses.get(i), teacherOneUser);
        }

        UUID assessment20 = assessment(school, assignmentA, semester, teacherOneUser, "Bài 20 điểm", new BigDecimal("20"), LocalDate.of(2026, 9, 6));
        UUID assessment10 = assessment(school, assignmentA, semester, teacherOneUser, "Bài 10 điểm", new BigDecimal("10"), LocalDate.of(2026, 9, 7));
        score(school, assessment20, studentA, teacherOneUser, new BigDecimal("10"));
        score(school, assessment10, studentA, teacherOneUser, new BigDecimal("8"));

        return new Fixture(school, admin, teacherOneUser, teacherTwoUser, year, studentA, studentB);
    }

    private static UUID school(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)", id, prefix + "-" + id.toString().substring(0, 8), prefix + " School");
        return id;
    }

    private static UUID user(String prefix, UUID schoolId, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')", id, prefix + "-" + id + "@example.com", "unused", prefix);
        jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')", id, schoolId, role);
        return id;
    }

    private static UUID teacher(UUID schoolId, UUID userId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')", id, schoolId, userId, code, code);
        return id;
    }

    private static UUID classroom(UUID schoolId, UUID yearId, UUID homeroom, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,homeroom_teacher_id,status) VALUES (?,?,?,?,?,?,'ACTIVE')", id, schoolId, yearId, code, code, homeroom);
        return id;
    }

    private static UUID student(UUID schoolId, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.students(id,school_id,student_code,full_name,status) VALUES (?,?,?,?,'ACTIVE')", id, schoolId, code, name);
        return id;
    }

    private static void enroll(UUID schoolId, UUID classroomId, UUID studentId) {
        jdbc.update("INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')", UUID.randomUUID(), schoolId, classroomId, studentId, LocalDate.of(2026, 8, 1));
    }

    private static UUID assignment(UUID schoolId, UUID teacherId, UUID classroomId, UUID subjectId, UUID semesterId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,semester_id,status) VALUES (?,?,?,?,?,?,'ACTIVE')", id, schoolId, teacherId, classroomId, subjectId, semesterId);
        return id;
    }

    private static UUID assessment(UUID schoolId, UUID assignmentId, UUID semesterId, UUID teacherUser, String title, BigDecimal maxScore, LocalDate date) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO grading.assessments(id,school_id,teaching_assignment_id,semester_id,title,category,max_score,weight,assessment_date,status,created_by) VALUES (?,?,?,?,?,'QUIZ',?,1,?,'SUBMITTED',?)",
                id, schoolId, assignmentId, semesterId, title, maxScore, date, teacherUser);
        return id;
    }

    private static void score(UUID schoolId, UUID assessmentId, UUID studentId, UUID teacherUser, BigDecimal value) {
        jdbc.update("INSERT INTO grading.student_scores(id,school_id,assessment_id,student_id,score,status,recorded_by) VALUES (?,?,?,?,?,'SUBMITTED',?)",
                UUID.randomUUID(), schoolId, assessmentId, studentId, value, teacherUser);
    }

    private record Fixture(UUID schoolId, UUID adminUser, UUID teacherOneUser, UUID teacherTwoUser,
                           UUID yearId, UUID studentA, UUID studentB) {}
}
