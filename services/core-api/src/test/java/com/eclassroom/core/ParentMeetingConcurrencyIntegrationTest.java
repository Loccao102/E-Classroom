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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class ParentMeetingConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_meeting_race")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static ParentMeetingService meetings;
    static TransactionTemplate tx;

    @BeforeAll
    static void setUp() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        jdbc = new JdbcTemplate(ds);
        JsonMapper json = JsonMapper.builder().build();
        AccessService access = new AccessService(jdbc);
        NotificationService notifications = new NotificationService(jdbc, new OutboxService(jdbc, json));
        meetings = new ParentMeetingService(jdbc, access, notifications, new AuditService(jdbc, json));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @Test
    void twoGuardiansRacingForOneSlotProduceExactlyOneWinner() throws Exception {
        Fixture f = fixture();
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
        UUID meetingId = meetings.create(f.school(), new ParentMeetingService.CreateCommand(
                "CLASSROOM", f.classroom(), "Race test", "Concurrency", null, "Room 1",
                start, start.plusHours(1), false,
                List.of(new ParentMeetingService.SlotCommand(start.plusMinutes(10), start.plusMinutes(20)))), f.teacher());

        var inviteeA = meetings.details(meetingId, f.parentA()).invitees().getFirst();
        var inviteeB = meetings.details(meetingId, f.parentB()).invitees().getFirst();
        meetings.respond(meetingId, f.studentA(), "ACCEPTED", inviteeA.version(), f.parentA());
        meetings.respond(meetingId, f.studentB(), "ACCEPTED", inviteeB.version(), f.parentB());
        var slot = meetings.details(meetingId, f.parentA()).slots().getFirst();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> a = pool.submit(() -> raceBook(ready, go, meetingId, slot.id(), f.studentA(), f.parentA(), slot.version()));
            Future<Result> b = pool.submit(() -> raceBook(ready, go, meetingId, slot.id(), f.studentB(), f.parentB(), slot.version()));
            ready.await();
            go.countDown();

            Result ra = a.get();
            Result rb = b.get();
            long winners = List.of(ra, rb).stream().filter(Result::success).count();
            assertEquals(1, winners);
            Result loser = ra.success() ? rb : ra;
            assertTrue(Set.of("VERSION_CONFLICT", "MEETING_SLOT_BOOKED").contains(loser.code()));

            assertEquals(1, count("SELECT COUNT(*) FROM communication.meeting_slots WHERE id=? AND booked_by_guardian_user_id IS NOT NULL", slot.id()));
            assertEquals(1, count("SELECT COUNT(*) FROM audit.audit_entries WHERE entity_type='MEETING_SLOT' AND entity_id=? AND action='BOOK_SLOT'", slot.id()));
            Long version = jdbc.queryForObject("SELECT version FROM communication.meeting_slots WHERE id=?", Long.class, slot.id());
            assertEquals(1L, version);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Result raceBook(CountDownLatch ready, CountDownLatch go, UUID meetingId, UUID slotId,
                                   UUID studentId, UUID parentId, long version) throws Exception {
        ready.countDown();
        go.await();
        try {
            tx.executeWithoutResult(status -> meetings.bookSlot(meetingId, slotId, studentId, version, parentId));
            return new Result(true, null);
        } catch (ApiException ex) {
            return new Result(false, ex.code());
        }
    }

    private static Fixture fixture() {
        UUID school = UUID.randomUUID();
        jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)", school, "RACE", "Race School");
        UUID teacherUser = user("teacher", school, "TEACHER");
        UUID parentA = user("parent-a", school, "PARENT");
        UUID parentB = user("parent-b", school, "PARENT");
        UUID studentUserA = user("student-a", school, "STUDENT");
        UUID studentUserB = user("student-b", school, "STUDENT");

        UUID year = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                year, school, "2026-2027", LocalDate.of(2026, 8, 1), LocalDate.of(2027, 6, 30));
        UUID subject = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)", subject, school, "MATH", "Mathematics");
        UUID teacher = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                teacher, school, teacherUser, "T001", "Teacher");
        UUID classroom = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,homeroom_teacher_id,status) VALUES (?,?,?,?,?,?,'ACTIVE')",
                classroom, school, year, "10A1", "10A1", teacher);
        jdbc.update("INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), school, teacher, classroom, subject);

        UUID studentA = student(school, studentUserA, "S001", "Student A");
        UUID studentB = student(school, studentUserB, "S002", "Student B");
        enroll(school, classroom, studentA);
        enroll(school, classroom, studentB);
        guardian(school, studentA, parentA, "Parent A");
        guardian(school, studentB, parentB, "Parent B");
        return new Fixture(school, classroom, teacherUser, parentA, parentB, studentA, studentB);
    }

    private static UUID user(String name, UUID school, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                id, name + "." + id.toString().substring(0, 8) + "@example.com", "unused", name);
        jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')", id, school, role);
        return id;
    }

    private static UUID student(UUID school, UUID user, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.students(id,school_id,user_id,student_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                id, school, user, code, name);
        return id;
    }

    private static void enroll(UUID school, UUID classroom, UUID student) {
        jdbc.update("INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), school, classroom, student, LocalDate.of(2026, 8, 1));
    }

    private static void guardian(UUID school, UUID student, UUID user, String name) {
        UUID guardian = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.guardians(id,school_id,user_id,full_name,status) VALUES (?,?,?,?,'ACTIVE')", guardian, school, user, name);
        jdbc.update("INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact,notifications_enabled) VALUES (?,?,?,'PARENT',TRUE,TRUE)",
                school, student, guardian);
    }

    private static int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private record Result(boolean success, String code) {}
    private record Fixture(UUID school, UUID classroom, UUID teacher, UUID parentA, UUID parentB, UUID studentA, UUID studentB) {}
}
