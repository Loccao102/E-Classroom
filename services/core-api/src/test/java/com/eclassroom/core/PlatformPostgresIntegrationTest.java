package com.eclassroom.core;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.identity.AuthService;
import com.eclassroom.core.integration.OutboxService;
import com.eclassroom.core.shared.api.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PlatformPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_test")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static AccessService access;

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
        access = new AccessService(jdbc);
    }

    @Test
    void migrationsCreateCoreSchemasAndTables() {
        Integer schools = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='school' AND table_name='schools'",
                Integer.class);
        Integer attendance = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='attendance' AND table_name='records'",
                Integer.class);
        Integer outbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='integration' AND table_name='outbox_events'",
                Integer.class);

        assertEquals(1, schools);
        assertEquals(1, attendance);
        assertEquals(1, outbox);
    }

    @Test
    void loginPersistsRefreshTokenAndReturnsHs256AccessToken() {
        String email = "login-" + UUID.randomUUID() + "@example.com";
        String rawPassword = "StrongPass123!";
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity.users(id,email,password_hash,full_name,platform_role,status) VALUES (?,?,?,?,?,'ACTIVE')",
                userId, email, passwordEncoder.encode(rawPassword), "Login Test", "SUPER_ADMIN");

        byte[] secretBytes = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        SecretKey secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
        AuthService auth = new AuthService(jdbc, passwordEncoder, encoder, "eclassroom-test", 15, 30);

        AuthService.TokenResponse tokens = auth.login(email, rawPassword);

        assertFalse(tokens.accessToken().isBlank());
        assertFalse(tokens.refreshToken().isBlank());
        assertEquals("Bearer", tokens.tokenType());
        Integer refreshRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity.refresh_tokens WHERE user_id=? AND revoked_at IS NULL AND expires_at>NOW()",
                Integer.class,
                userId);
        assertEquals(1, refreshRows);
        assertThrows(ApiException.class, () -> auth.login(email, "wrong-password"));
    }

    @Test
    void membershipIsTenantScoped() {
        UUID schoolA = school("TENANT-A-" + UUID.randomUUID(), "Tenant A");
        UUID schoolB = school("TENANT-B-" + UUID.randomUUID(), "Tenant B");
        UUID teacher = user("teacher-" + UUID.randomUUID() + "@example.com", null);
        membership(teacher, schoolA, "TEACHER");

        assertDoesNotThrow(() -> access.requireMembership(schoolA, teacher));
        assertThrows(ApiException.class, () -> access.requireMembership(schoolB, teacher));
    }

    @Test
    void teachingAssignmentAuthorizationIsResourceScoped() {
        UUID school = school("ASSIGN-" + UUID.randomUUID(), "Assignment School");
        UUID assignedUser = user("assigned-" + UUID.randomUUID() + "@example.com", null);
        UUID otherTeacher = user("other-" + UUID.randomUUID() + "@example.com", null);
        membership(assignedUser, school, "TEACHER");
        membership(otherTeacher, school, "TEACHER");

        UUID year = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                year, school, "2026-2027-" + year.toString().substring(0, 8), LocalDate.of(2026, 8, 1), LocalDate.of(2027, 6, 30));
        UUID classroom = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                classroom, school, year, "10A1-" + classroom.toString().substring(0, 8), "Class 10A1");
        UUID subject = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)",
                subject, school, "MATH-" + subject.toString().substring(0, 8), "Mathematics");
        UUID teacherProfile = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                teacherProfile, school, assignedUser, "GV-" + teacherProfile.toString().substring(0, 8), "Assigned Teacher");
        UUID assignment = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,status) VALUES (?,?,?,?,?,'ACTIVE')",
                assignment, school, teacherProfile, classroom, subject);

        assertDoesNotThrow(() -> access.requireTeacherAssignment(school, assignedUser, assignment));
        assertThrows(ApiException.class, () -> access.requireTeacherAssignment(school, otherTeacher, assignment));
    }

    @Test
    void outboxPersistsVersionedRecipientEnvelope() throws Exception {
        UUID school = school("OUTBOX-" + UUID.randomUUID(), "Outbox School");
        UUID recipient = user("guardian-" + UUID.randomUUID() + "@example.com", null);
        OutboxService outbox = new OutboxService(jdbc, JsonMapper.builder().build());
        UUID entityId = UUID.randomUUID();

        UUID eventId = outbox.emit(
                school,
                "student.attendance.changed",
                1,
                Map.of("entityId", entityId, "entityType", "ATTENDANCE_SESSION", "title", "Attendance update"),
                List.of(recipient));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT event_type,event_version,payload::text payload,published_at FROM integration.outbox_events WHERE id=?",
                eventId);
        String payload = String.valueOf(row.get("payload"));
        assertEquals("student.attendance.changed", row.get("event_type"));
        assertEquals(1, ((Number) row.get("event_version")).intValue());
        assertTrue(payload.contains(eventId.toString()));
        assertTrue(payload.contains(recipient.toString()));
        assertTrue(payload.contains("ATTENDANCE_SESSION"));
        assertNull(row.get("published_at"));
    }

    private static UUID school(String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)", id, code, name);
        return id;
    }

    private static UUID user(String email, String platformRole) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity.users(id,email,password_hash,full_name,platform_role,status) VALUES (?,?,?,?,?,'ACTIVE')",
                id, email, "not-used-in-this-test", email, platformRole);
        return id;
    }

    private static void membership(UUID userId, UUID schoolId, String role) {
        jdbc.update(
                "INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')",
                userId, schoolId, role);
    }
}
