package com.eclassroom.core;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.integration.BulkImportJobService;
import com.eclassroom.core.integration.BulkImportParser;
import com.eclassroom.core.integration.BulkImportValidator;
import com.eclassroom.core.shared.api.ApiException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class BulkImportIdempotencyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_import_race")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static BulkImportJobService imports;
    static TransactionTemplate tx;
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
        AccessService access = new AccessService(jdbc);
        JsonMapper json = JsonMapper.builder().build();
        imports = new BulkImportJobService(jdbc, access, json, new BulkImportParser(), new BulkImportValidator(jdbc));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        schoolId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)", schoolId, "IDEMP-RACE", "Import Idempotency School");
        jdbc.update("INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                adminId, "import-race@example.com", "unused", "Import Admin");
        jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?, 'SCHOOL_ADMIN','ACTIVE')",
                adminId, schoolId);
    }

    private static MockMultipartFile file(String name) {
        String csv = "student_code,full_name,date_of_birth,gender,email\n"
                + "S001," + name + ",2012-01-01,M,test@example.com\n";
        return new MockMultipartFile("file", "students.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void simultaneousIdenticalStageRequestsShareOneJob() throws Exception {
        String key = "race-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> stageWhenReleased(ready, go, key, "Student A"));
            var b = pool.submit(() -> stageWhenReleased(ready, go, key, "Student A"));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            var first = a.get(20, TimeUnit.SECONDS);
            var second = b.get(20, TimeUnit.SECONDS);
            assertEquals(first.jobId(), second.jobId());
            assertEquals(1, List.of(first, second).stream().filter(result -> !result.idempotentReplay()).count());
            assertEquals(1, List.of(first, second).stream().filter(BulkImportJobService.StageResult::idempotentReplay).count());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM integration.import_jobs WHERE school_id=? AND idempotency_key=?",
                    Integer.class, schoolId, key));
        }
    }

    @Test
    void simultaneousDifferentPayloadsDoNotOverwriteOrReturnServerError() throws Exception {
        String key = "conflicting-race-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> raceStageOutcome(ready, go, key, "Student A"));
            var b = pool.submit(() -> raceStageOutcome(ready, go, key, "Student B"));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(outcome -> outcome.startsWith("job:")).count());
            assertEquals(1, results.stream().filter("IDEMPOTENCY_KEY_REUSED"::equals).count());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM integration.import_jobs WHERE school_id=? AND idempotency_key=?",
                    Integer.class, schoolId, key));
        }
    }

    private static String raceStageOutcome(
            CountDownLatch ready, CountDownLatch go, String key, String name) throws InterruptedException {
        try {
            return "job:" + stageWhenReleased(ready, go, key, name).jobId();
        } catch (ApiException ex) {
            return ex.code();
        }
    }

    @Test
    void reusedKeyWithDifferentPayloadStillConflicts() {
        String key = "payload-" + UUID.randomUUID();
        var first = tx.execute(status -> imports.stage(schoolId, adminId, "STUDENT", key, file("Student A")));
        assertNotNull(first);
        ApiException thrown = assertThrows(ApiException.class,
                () -> tx.execute(status -> imports.stage(schoolId, adminId, "STUDENT", key, file("Student B"))));
        assertEquals("IDEMPOTENCY_KEY_REUSED", thrown.code());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration.import_jobs WHERE school_id=? AND idempotency_key=?",
                Integer.class, schoolId, key));
    }

    @Test
    void reusedKeyWithIdenticalBytesButDifferentFormatStillConflicts() throws Exception {
        String key = "format-" + UUID.randomUUID();
        MockMultipartFile csv = file("Student A");
        MockMultipartFile mislabeledXlsx = new MockMultipartFile(
                "file", "students.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                csv.getBytes());
        var first = tx.execute(status -> imports.stage(schoolId, adminId, "STUDENT", key, csv));
        assertNotNull(first);
        ApiException thrown = assertThrows(ApiException.class,
                () -> tx.execute(status -> imports.stage(schoolId, adminId, "STUDENT", key, mislabeledXlsx)));
        assertEquals("IDEMPOTENCY_KEY_REUSED", thrown.code());
        assertEquals(first.jobId(), jdbc.queryForObject(
                "SELECT id FROM integration.import_jobs WHERE school_id=? AND idempotency_key=?",
                UUID.class, schoolId, key));
    }

    private static BulkImportJobService.StageResult stageWhenReleased(
            CountDownLatch ready, CountDownLatch go, String key, String name) throws InterruptedException {
        ready.countDown();
        if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test did not start");
        return tx.execute(status -> imports.stage(schoolId, adminId, "STUDENT", key, file(name)));
    }
}
