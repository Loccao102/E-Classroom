package com.eclassroom.core;

import com.eclassroom.core.academic.BulkAcademicImportWriter;
import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.integration.BulkImportCommitService;
import com.eclassroom.core.integration.BulkImportJobService;
import com.eclassroom.core.integration.BulkImportParser;
import com.eclassroom.core.integration.BulkImportValidator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class BulkImportPerformanceIT {
    private static final int ROWS = 5_000;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_bulk_perf")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static BulkImportJobService jobs;
    static BulkImportCommitService commits;
    static UUID schoolId;
    static UUID adminId;

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
        jobs = new BulkImportJobService(jdbc, access, json, new BulkImportParser(), new BulkImportValidator(jdbc));
        commits = new BulkImportCommitService(jdbc, access, json, new AuditService(jdbc, json), new BulkAcademicImportWriter(jdbc));

        schoolId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)", schoolId, "PERF", "Performance School");
        jdbc.update("INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                adminId, "perf-admin@example.com", "unused", "Performance Admin");
        jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?, 'SCHOOL_ADMIN','ACTIVE')",
                adminId, schoolId);
    }

    @Test
    void fiveThousandStudentRowsReachPreviewAndCommitWithinBudget() {
        byte[] csv = csv(ROWS).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "students.csv", "text/csv", csv);
        var staged = jobs.stage(schoolId, adminId, "STUDENT", "perf-5000", file);

        long validationStart = System.nanoTime();
        assertTimeout(Duration.ofSeconds(20), () -> jobs.process(staged.jobId()));
        long validationMs = elapsedMs(validationStart);

        var ready = jobs.job(schoolId, adminId, staged.jobId());
        assertEquals("PREVIEW_READY", ready.status());
        assertEquals(ROWS, ready.validRows());
        assertEquals(0, ready.invalidRows());

        long commitStart = System.nanoTime();
        var result = assertTimeout(Duration.ofSeconds(20), () -> commits.commit(schoolId, adminId, staged.jobId(), ready.version()));
        long commitMs = elapsedMs(commitStart);

        assertEquals(ROWS, result.committedRows());
        assertEquals(ROWS, count("SELECT COUNT(*) FROM academic.students WHERE school_id=?", schoolId));
        assertEquals(ROWS, count("SELECT COUNT(*) FROM integration.import_rows WHERE job_id=? AND status='COMMITTED' AND committed_entity_id IS NOT NULL", staged.jobId()));
        assertTrue(commitMs < 20_000);

        System.out.printf("PERF bulk-import rows=%d validation_ms=%d commit_ms=%d%n", ROWS, validationMs, commitMs);
    }

    private static String csv(int rows) {
        StringBuilder out = new StringBuilder(rows * 80);
        out.append("student_code,full_name,date_of_birth,gender,email\n");
        for (int i = 0; i < rows; i++) {
            out.append("PERF").append(String.format("%05d", i))
                    .append(",Student ").append(i)
                    .append(",2012-01-01,M,perf").append(i).append("@example.com\n");
        }
        return out.toString();
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
