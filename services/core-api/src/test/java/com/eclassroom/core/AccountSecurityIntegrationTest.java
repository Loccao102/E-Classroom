package com.eclassroom.core;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.identity.AccountSecurityService;
import com.eclassroom.core.identity.AuthService;
import com.eclassroom.core.identity.SecurityEventService;
import com.eclassroom.core.shared.api.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(AccountSecurityIntegrationTest.TestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountSecurityIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("eclassroom_security_test")
                .withUsername("eclassroom")
                .withPassword("eclassroom");
        POSTGRES.start();
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuthService auth;
    private final AccountSecurityService accounts;
    private final AuthService.ClientContext client;

    @Autowired
    AccountSecurityIntegrationTest(JdbcTemplate jdbc,
                                   PasswordEncoder passwords,
                                   AuthService auth,
                                   AccountSecurityService accounts) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.auth = auth;
        this.accounts = accounts;
        this.client = auth.clientContext("Mozilla/5.0 Chrome/152 Windows", "127.0.0.1");
    }

    @AfterAll
    void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE identity.security_events, identity.login_attempts, identity.refresh_tokens, " +
                "identity.school_memberships, identity.users, school.schools CASCADE");
    }

    @Test
    void failedAttemptsPersistAcrossThrownExceptionsAndThrottleCorrectPassword() {
        TestUser user = user("throttle", "StrongPass123!", "ACTIVE", false, null);

        for (int i = 0; i < 3; i++) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> auth.login(user.email(), "WrongPass123!", client));
            assertEquals("INVALID_CREDENTIALS", failure.code());
        }

        Integer failed = jdbc.queryForObject("SELECT failed_count FROM identity.login_attempts", Integer.class);
        Boolean locked = jdbc.queryForObject("SELECT locked_until>NOW() FROM identity.login_attempts", Boolean.class);
        assertEquals(3, failed);
        assertTrue(Boolean.TRUE.equals(locked));

        ApiException blocked = assertThrows(ApiException.class,
                () -> auth.login(user.email(), user.password(), client));
        assertEquals("INVALID_CREDENTIALS", blocked.code());
        Integer securityEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity.security_events WHERE event_type IN ('LOGIN')", Integer.class);
        assertEquals(4, securityEvents);
    }

    @Test
    void refreshRotationKeepsSessionAndReplayRevokesAllTokens() {
        TestUser user = user("rotation", "StrongPass123!", "ACTIVE", false, null);
        AuthService.TokenResponse first = auth.login(user.email(), user.password(), client);
        UUID originalSession = activeSession(user.id());

        AuthService.TokenResponse rotated = auth.refresh(first.refreshToken(), client);
        UUID rotatedSession = activeSession(user.id());
        assertEquals(originalSession, rotatedSession);
        assertNotEquals(first.refreshToken(), rotated.refreshToken());
        assertEquals("ROTATED", jdbc.queryForObject(
                "SELECT revoked_reason FROM identity.refresh_tokens WHERE user_id=? AND revoked_reason='ROTATED' LIMIT 1",
                String.class, user.id()));

        ApiException replay = assertThrows(ApiException.class,
                () -> auth.refresh(first.refreshToken(), client));
        assertEquals("INVALID_REFRESH_TOKEN", replay.code());
        assertEquals(0, activeRefreshCount(user.id()));
        assertEquals(1L, jdbc.queryForObject("SELECT token_version FROM identity.users WHERE id=?", Long.class, user.id()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity.security_events WHERE user_id=? AND event_type='REFRESH_REPLAY'",
                Integer.class, user.id()));
    }

    @Test
    void logoutAndPasswordChangeRevokeSessions() {
        TestUser user = user("password", "StrongPass123!", "ACTIVE", true, null);
        AuthService.TokenResponse login = auth.login(user.email(), user.password(), client);
        assertEquals(1, activeRefreshCount(user.id()));

        auth.logout(login.refreshToken());
        assertEquals(0, activeRefreshCount(user.id()));

        auth.login(user.email(), user.password(), client);
        auth.changePassword(user.id(), user.password(), "EvenStronger456!");
        assertEquals(0, activeRefreshCount(user.id()));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT must_change_password FROM identity.users WHERE id=?", Boolean.class, user.id())));
        assertTrue(passwords.matches("EvenStronger456!", jdbc.queryForObject(
                "SELECT password_hash FROM identity.users WHERE id=?", String.class, user.id())));
        assertEquals(1L, jdbc.queryForObject("SELECT token_version FROM identity.users WHERE id=?", Long.class, user.id()));
    }

    @Test
    void schoolAdminCanDisableAndIssueForcedTemporaryPasswordForManagedAccount() {
        UUID schoolId = school();
        TestUser admin = user("admin", "AdminStrong123!", "ACTIVE", false, null);
        TestUser student = user("student", "StudentStrong123!", "ACTIVE", false, null);
        membership(admin.id(), schoolId, "SCHOOL_ADMIN");
        membership(student.id(), schoolId, "STUDENT");
        AuthService.TokenResponse studentSession = auth.login(student.email(), student.password(), client);
        assertNotNull(studentSession.accessToken());

        AccountSecurityService.AccountStatusResult disabled = accounts.status(schoolId, admin.id(), student.id(), "DISABLED");
        assertEquals("DISABLED", disabled.status());
        assertEquals(0, activeRefreshCount(student.id()));
        ApiException disabledLogin = assertThrows(ApiException.class,
                () -> auth.login(student.email(), student.password(), client));
        assertEquals("INVALID_CREDENTIALS", disabledLogin.code());

        AccountSecurityService.TemporaryPasswordResult reset = accounts.temporaryPassword(schoolId, admin.id(), student.id());
        assertTrue(reset.mustChangePassword());
        assertTrue(reset.temporaryPassword().startsWith("Tmp9-"));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT must_change_password FROM identity.users WHERE id=?", Boolean.class, student.id())));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM identity.users WHERE id=?", String.class, student.id()));
        AuthService.TokenResponse tempLogin = auth.login(student.email(), reset.temporaryPassword(), client);
        assertTrue(tempLogin.mustChangePassword());
    }

    private TestUser user(String prefix, String password, String status, boolean mustChange, String platformRole) {
        UUID id = UUID.randomUUID();
        String email = prefix + "-" + id.toString().substring(0, 8) + "@example.com";
        jdbc.update(
                "INSERT INTO identity.users(id,email,password_hash,full_name,platform_role,status,must_change_password) VALUES (?,?,?,?,?,?,?)",
                id, email, passwords.encode(password), prefix, platformRole, status, mustChange);
        return new TestUser(id, email, password);
    }

    private UUID school() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)",
                id, "SEC-" + id.toString().substring(0, 8), "Security School");
        return id;
    }

    private void membership(UUID userId, UUID schoolId, String role) {
        jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')",
                userId, schoolId, role);
    }

    private UUID activeSession(UUID userId) {
        return jdbc.queryForObject(
                "SELECT session_id FROM identity.refresh_tokens WHERE user_id=? AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1",
                UUID.class, userId);
    }

    private int activeRefreshCount(UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity.refresh_tokens WHERE user_id=? AND revoked_at IS NULL AND expires_at>NOW()",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private record TestUser(UUID id, String email, String password) {}

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setURL(POSTGRES.getJdbcUrl());
            ds.setUser(POSTGRES.getUsername());
            ds.setPassword(POSTGRES.getPassword());
            return ds;
        }

        @Bean
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
            flyway.migrate();
            return flyway;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource, Flyway ignored) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        JwtEncoder jwtEncoder() {
            byte[] bytes = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
            SecretKey key = new SecretKeySpec(bytes, "HmacSHA256");
            return new NimbusJwtEncoder(new ImmutableSecret<>(key));
        }

        @Bean
        SecurityEventService securityEventService(JdbcTemplate jdbc) {
            return new SecurityEventService(jdbc, JsonMapper.builder().build());
        }

        @Bean
        AccessService accessService(JdbcTemplate jdbc) {
            return new AccessService(jdbc);
        }

        @Bean
        AuthService authService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder encoder, SecurityEventService events) {
            return new AuthService(jdbc, passwords, encoder, events, "eclassroom-security-test", 15, 30, 3, 10, 15);
        }

        @Bean
        AccountSecurityService accountSecurityService(JdbcTemplate jdbc,
                                                      PasswordEncoder passwords,
                                                      AccessService access,
                                                      AuthService auth,
                                                      SecurityEventService events) {
            return new AccountSecurityService(jdbc, passwords, access, auth, events);
        }
    }
}
