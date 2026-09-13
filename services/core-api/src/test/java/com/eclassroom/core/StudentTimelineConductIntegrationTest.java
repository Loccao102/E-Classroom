package com.eclassroom.core;

import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.conduct.ConductService;
import com.eclassroom.core.conduct.StudentTimelineService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.integration.OutboxService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.ApiException;
import com.eclassroom.core.shared.api.CorrelationIdFilter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class StudentTimelineConductIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_timeline_test")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static ConductService conduct;
    static StudentTimelineService timeline;

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
        AuditService audit = new AuditService(jdbc, json);
        conduct = new ConductService(jdbc, access, notifications, audit, json);
        timeline = new StudentTimelineService(jdbc, access);
    }

    @Test
    void conductVisibilitySeparatesStaffGuardianAndStudentAndNotifiesOnlyAudience() {
        Fixture f = fixture("VIS");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);

        ConductService.ConductView staff = conduct.create(
                f.schoolId(), f.studentId(), command("GENERAL", null, "Staff only", "Internal note", "STAFF_ONLY", f, now), f.teacherUser());
        ConductService.ConductView guardian = conduct.create(
                f.schoolId(), f.studentId(), command("REMINDER", "LOW", "Guardian reminder", "Bring books", "GUARDIAN", f, now.minusMinutes(1)), f.teacherUser());
        ConductService.ConductView student = conduct.create(
                f.schoolId(), f.studentId(), command("ACHIEVEMENT", "INFO", "Student achievement", "Great presentation", "STUDENT", f, now.minusMinutes(2)), f.teacherUser());
        ConductService.ConductView shared = conduct.create(
                f.schoolId(), f.studentId(), command("POSITIVE_RECOGNITION", "INFO", "Shared recognition", "Excellent teamwork", "STUDENT_AND_GUARDIAN", f, now.minusMinutes(3)), f.teacherUser());

        List<ConductService.ConductView> teacherView = conduct.list(f.schoolId(), f.studentId(), f.teacherUser());
        List<ConductService.ConductView> parentView = conduct.list(f.schoolId(), f.studentId(), f.parentUser());
        List<ConductService.ConductView> studentView = conduct.list(f.schoolId(), f.studentId(), f.studentUser());
        assertEquals(4, teacherView.size());
        assertEquals(Set.of(guardian.id(), shared.id()), ids(parentView));
        assertEquals(Set.of(student.id(), shared.id()), ids(studentView));
        assertFalse(ids(parentView).contains(staff.id()));

        assertEquals(0, count("SELECT COUNT(*) FROM notification.notifications WHERE entity_id=?", staff.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM notification.notifications WHERE entity_id=? AND recipient_user_id=?", guardian.id(), f.parentUser()));
        assertEquals(1, count("SELECT COUNT(*) FROM notification.notifications WHERE entity_id=? AND recipient_user_id=?", student.id(), f.studentUser()));
        assertEquals(2, count("SELECT COUNT(*) FROM notification.notifications WHERE entity_id=?", shared.id()));
        assertEquals(4, count("SELECT COUNT(*) FROM integration.outbox_events WHERE event_type='student.conduct.created' AND school_id=?", f.schoolId()));

        ApiException denied = assertThrows(ApiException.class,
                () -> conduct.create(f.schoolId(), f.studentId(), command("GENERAL", null, "Nope", "Unrelated", "STAFF_ONLY", f, now), f.unrelatedTeacherUser()));
        assertEquals("FORBIDDEN", denied.code());
    }

    @Test
    void updateUsesOptimisticVersionAndAppendsRevisionAndCorrelatedAudit() {
        Fixture f = fixture("REV");
        ConductService.ConductView created = conduct.create(
                f.schoolId(), f.studentId(), command("REMINDER", "LOW", "Original", "Initial note", "GUARDIAN", f,
                        OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)), f.teacherUser());

        String correlation = "timeline-test-" + UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlation);
        ConductService.ConductView updated;
        try {
            updated = conduct.update(
                    f.schoolId(), f.studentId(), created.id(), 0, "Clarified after speaking with student",
                    command("POSITIVE_RECOGNITION", "INFO", "Improved", "Student corrected the issue", "STUDENT_AND_GUARDIAN", f, created.occurredAt()),
                    f.teacherUser());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        assertEquals(1L, updated.version());
        assertEquals(1, count("SELECT COUNT(*) FROM conduct.revisions WHERE conduct_record_id=?", created.id()));
        assertEquals(correlation, jdbc.queryForObject(
                "SELECT correlation_id FROM conduct.revisions WHERE conduct_record_id=?", String.class, created.id()));
        assertEquals(2, count("SELECT COUNT(*) FROM audit.audit_entries WHERE entity_type='CONDUCT_RECORD' AND entity_id=?", created.id()));
        assertEquals(correlation, jdbc.queryForObject(
                "SELECT correlation_id FROM audit.audit_entries WHERE entity_type='CONDUCT_RECORD' AND entity_id=? AND action='UPDATE'",
                String.class, created.id()));

        ApiException stale = assertThrows(ApiException.class,
                () -> conduct.update(f.schoolId(), f.studentId(), created.id(), 0, "stale update",
                        command("GENERAL", null, "Stale", "Must fail", "STAFF_ONLY", f, created.occurredAt()), f.teacherUser()));
        assertEquals("VERSION_CONFLICT", stale.code());
        assertEquals(1, count("SELECT COUNT(*) FROM conduct.revisions WHERE conduct_record_id=?", created.id()));
    }

    @Test
    void timelineCombinesAuthorizedSourcesAndHidesDraftScores() {
        Fixture f = fixture("MIX");
        seedAcademicTimeline(f);
        ConductService.ConductView shared = conduct.create(
                f.schoolId(), f.studentId(), command("ACHIEVEMENT", "INFO", "Conduct event", "Visible to family", "STUDENT_AND_GUARDIAN", f,
                        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3)), f.teacherUser());
        ConductService.ConductView staff = conduct.create(
                f.schoolId(), f.studentId(), command("GENERAL", null, "Private conduct", "Staff detail", "STAFF_ONLY", f,
                        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(4)), f.teacherUser());

        StudentTimelineService.TimelinePage parent = timeline.page(
                f.schoolId(), f.studentId(), f.parentUser(), 50, null, null, null, null, null);
        Set<String> types = parent.items().stream().map(StudentTimelineService.TimelineItem::type).collect(java.util.stream.Collectors.toSet());
        assertTrue(types.containsAll(Set.of("ATTENDANCE", "LEAVE", "SCORE", "COMMENT", "CONDUCT", "ANNOUNCEMENT")));
        assertTrue(parent.items().stream().anyMatch(item -> item.id().equals(shared.id())));
        assertFalse(parent.items().stream().anyMatch(item -> item.id().equals(staff.id())));
        assertFalse(parent.items().stream().anyMatch(item -> "Draft score must stay hidden".equals(item.title())));

        StudentTimelineService.TimelinePage teacher = timeline.page(
                f.schoolId(), f.studentId(), f.teacherUser(), 50, null, null, null, null, List.of("CONDUCT"));
        assertTrue(teacher.items().stream().anyMatch(item -> item.id().equals(staff.id())));
        assertTrue(teacher.items().stream().allMatch(item -> "CONDUCT".equals(item.type())));

        StudentTimelineService.TimelinePage student = timeline.page(
                f.schoolId(), f.studentId(), f.studentUser(), 50, null, null, null, null, List.of("CONDUCT", "COMMENT"));
        assertFalse(student.items().stream().anyMatch(item -> item.id().equals(staff.id())));
    }

    @Test
    void timelineKeysetPaginationIsDeterministicWithoutDuplicates() {
        Fixture f = fixture("PAGE");
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        for (int i = 0; i < 5; i++) {
            conduct.create(f.schoolId(), f.studentId(),
                    command("GENERAL", "INFO", "Event " + i, "Body " + i, "STUDENT_AND_GUARDIAN", f, base.minusMinutes(i)),
                    f.teacherUser());
        }

        StudentTimelineService.TimelinePage first = timeline.page(
                f.schoolId(), f.studentId(), f.parentUser(), 2, null, null, null, null, List.of("CONDUCT"));
        assertEquals(2, first.items().size());
        assertNotNull(first.nextCursor());
        StudentTimelineService.TimelinePage second = timeline.page(
                f.schoolId(), f.studentId(), f.parentUser(), 2,
                first.nextCursor().beforeOccurredAt(), first.nextCursor().beforeId(), null, null, List.of("CONDUCT"));
        assertEquals(2, second.items().size());
        Set<UUID> seen = new HashSet<>();
        first.items().forEach(item -> assertTrue(seen.add(item.id())));
        second.items().forEach(item -> assertTrue(seen.add(item.id())));

        LocalDate schoolDay = base.atZoneSameInstant(ZoneId.of("Asia/Bangkok")).toLocalDate();
        StudentTimelineService.TimelinePage filtered = timeline.page(
                f.schoolId(), f.studentId(), f.parentUser(), 20, null, null,
                schoolDay, schoolDay, List.of("CONDUCT"));
        assertTrue(filtered.items().size() >= 1);
        assertTrue(filtered.items().stream().allMatch(item -> item.type().equals("CONDUCT")));
    }

    private static ConductService.Command command(String category, String severity, String title, String body,
                                                   String visibility, Fixture f, OffsetDateTime occurredAt) {
        return new ConductService.Command(category, severity, title, body, visibility, f.classroomId(), f.subjectId(), occurredAt);
    }

    private static Set<UUID> ids(List<ConductService.ConductView> rows) {
        return rows.stream().map(ConductService.ConductView::id).collect(java.util.stream.Collectors.toSet());
    }

    private static void seedAcademicTimeline(Fixture f) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO attendance.sessions(id,school_id,classroom_id,teaching_assignment_id,attendance_date,period,status,created_by) VALUES (?,?,?,?,?,1,'CLOSED',?)",
                session, f.schoolId(), f.classroomId(), f.assignmentId(), LocalDate.now(), f.teacherUser());
        jdbc.update("INSERT INTO attendance.records(id,school_id,attendance_session_id,student_id,status,note,marked_by) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), f.schoolId(), session, f.studentId(), "LATE", "Traffic", f.teacherUser());

        jdbc.update("INSERT INTO attendance.leave_requests(id,school_id,student_id,guardian_user_id,start_date,end_date,reason,status,reviewed_by,reviewed_at) VALUES (?,?,?,?,?,?,?,'APPROVED',?,NOW())",
                UUID.randomUUID(), f.schoolId(), f.studentId(), f.parentUser(), LocalDate.now().minusDays(2), LocalDate.now().minusDays(2), "Medical appointment", f.teacherUser());

        UUID publishedAssessment = UUID.randomUUID();
        jdbc.update("INSERT INTO grading.assessments(id,school_id,teaching_assignment_id,title,category,max_score,weight,status,created_by,submitted_at,submitted_by) VALUES (?,?,?,?,?,10,1,'SUBMITTED',?,NOW(),?)",
                publishedAssessment, f.schoolId(), f.assignmentId(), "Published score", "QUIZ", f.teacherUser(), f.teacherUser());
        jdbc.update("INSERT INTO grading.student_scores(id,school_id,assessment_id,student_id,score,status,recorded_by) VALUES (?,?,?,?,?,'SUBMITTED',?)",
                UUID.randomUUID(), f.schoolId(), publishedAssessment, f.studentId(), new BigDecimal("8.50"), f.teacherUser());

        UUID draftAssessment = UUID.randomUUID();
        jdbc.update("INSERT INTO grading.assessments(id,school_id,teaching_assignment_id,title,category,max_score,weight,status,created_by) VALUES (?,?,?,?,?,10,1,'DRAFT',?)",
                draftAssessment, f.schoolId(), f.assignmentId(), "Draft score must stay hidden", "QUIZ", f.teacherUser());
        jdbc.update("INSERT INTO grading.student_scores(id,school_id,assessment_id,student_id,score,status,recorded_by) VALUES (?,?,?,?,?,'DRAFT',?)",
                UUID.randomUUID(), f.schoolId(), draftAssessment, f.studentId(), new BigDecimal("3.00"), f.teacherUser());

        jdbc.update("INSERT INTO communication.teacher_comments(id,school_id,student_id,teacher_user_id,body,visibility) VALUES (?,?,?,?,?,'STUDENT_AND_GUARDIAN')",
                UUID.randomUUID(), f.schoolId(), f.studentId(), f.teacherUser(), "Keep up the good work");
        jdbc.update("INSERT INTO communication.announcements(id,school_id,title,body,target_type,target_id,published_by,pinned) VALUES (?,?,?,?, 'CLASSROOM',?,?,TRUE)",
                UUID.randomUUID(), f.schoolId(), "Class announcement", "Remember tomorrow's materials", f.classroomId(), f.teacherUser());
    }

    private static Fixture fixture(String prefix) {
        UUID school = UUID.randomUUID();
        jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)", school, prefix + "-" + school.toString().substring(0, 6), prefix + " School");

        UUID teacherUser = user(prefix + "-teacher", school, "TEACHER");
        UUID unrelatedTeacherUser = user(prefix + "-other", school, "TEACHER");
        UUID parentUser = user(prefix + "-parent", school, "PARENT");
        UUID studentUser = user(prefix + "-student", school, "STUDENT");

        UUID year = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                year, school, "2026-2027-" + prefix, LocalDate.of(2026, 8, 1), LocalDate.of(2027, 5, 31));
        UUID grade = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.grade_levels(id,school_id,name,sort_order) VALUES (?,?,?,1)", grade, school, "10-" + prefix);
        UUID subject = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)", subject, school, "MATH-" + prefix, "Mathematics");

        UUID teacher = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                teacher, school, teacherUser, "T-" + prefix, "Teacher " + prefix);
        UUID otherTeacher = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                otherTeacher, school, unrelatedTeacherUser, "TO-" + prefix, "Other Teacher " + prefix);

        UUID classroom = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.classrooms(id,school_id,academic_year_id,grade_level_id,code,name,homeroom_teacher_id,status) VALUES (?,?,?,?,?,?,?,'ACTIVE')",
                classroom, school, year, grade, "C-" + prefix, "Class " + prefix, teacher);
        UUID student = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.students(id,school_id,user_id,student_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                student, school, studentUser, "S-" + prefix, "Student " + prefix);
        jdbc.update("INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), school, classroom, student, LocalDate.of(2026, 8, 1));

        UUID assignment = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,status) VALUES (?,?,?,?,?,'ACTIVE')",
                assignment, school, teacher, classroom, subject);
        UUID guardian = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.guardians(id,school_id,user_id,full_name,status) VALUES (?,?,?,?,'ACTIVE')",
                guardian, school, parentUser, "Parent " + prefix);
        jdbc.update("INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact,notifications_enabled) VALUES (?,?,?,'PARENT',TRUE,TRUE)",
                school, student, guardian);

        return new Fixture(school, classroom, subject, assignment, student, teacherUser, unrelatedTeacherUser, parentUser, studentUser);
    }

    private static UUID user(String prefix, UUID school, String role) {
        UUID id = UUID.randomUUID();
        String email = prefix.toLowerCase() + "-" + id.toString().substring(0, 6) + "@example.com";
        jdbc.update("INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')", id, email, "unused", prefix);
        jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')", id, school, role);
        return id;
    }

    private static int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private record Fixture(UUID schoolId, UUID classroomId, UUID subjectId, UUID assignmentId, UUID studentId,
                           UUID teacherUser, UUID unrelatedTeacherUser, UUID parentUser, UUID studentUser) {}
}
