package com.eclassroom.core.integration;

import com.eclassroom.core.academic.BulkAcademicImportWriter;
import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BulkImportCommitService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final JsonMapper json;
    private final AuditService audit;
    private final BulkAcademicImportWriter writer;

    public BulkImportCommitService(JdbcTemplate jdbc, AccessService access, JsonMapper json,
                                   AuditService audit, BulkAcademicImportWriter writer) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
        this.audit = audit;
        this.writer = writer;
    }

    @Transactional
    public CommitResult commit(UUID schoolId, UUID actor, UUID jobId, long expectedVersion) {
        access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN");
        List<Job> jobs = jdbc.query(
                "SELECT import_type,status,invalid_rows,version FROM integration.import_jobs WHERE school_id=? AND id=? FOR UPDATE",
                (rs, i) -> new Job(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4)), schoolId, jobId);
        if (jobs.isEmpty()) throw ApiException.notFound("Import job was not found");
        Job job = jobs.getFirst();
        if ("COMMITTED".equals(job.status())) return new CommitResult(jobId, 0, true);
        if (!"PREVIEW_READY".equals(job.status()) || job.invalidRows() != 0)
            throw ApiException.badRequest("IMPORT_NOT_COMMITTABLE", "Import must have a clean preview before commit");
        if (job.version() != expectedVersion)
            throw ApiException.conflict("VERSION_CONFLICT", "Import job changed; reload before commit");

        int changed = jdbc.update(
                "UPDATE integration.import_jobs SET status='COMMITTING',version=version+1,updated_at=NOW() WHERE id=? AND version=?",
                jobId, expectedVersion);
        if (changed != 1) throw ApiException.conflict("VERSION_CONFLICT", "Import job changed; reload before commit");

        List<RowData> rows = jdbc.query(
                "SELECT id,normalized_data::text FROM integration.import_rows WHERE job_id=? AND status='VALID' ORDER BY row_number",
                (rs, i) -> new RowData(rs.getObject(1, UUID.class), read(rs.getString(2))), jobId);
        int committed = 0;
        for (RowData row : rows) {
            UUID entityId = writer.apply(schoolId, job.type(), row.data());
            jdbc.update("UPDATE integration.import_rows SET status='COMMITTED',committed_entity_id=? WHERE id=?", entityId, row.id());
            committed++;
        }
        jdbc.update(
                "UPDATE integration.import_jobs SET status='COMMITTED',committed_by=?,committed_at=NOW(),version=version+1,updated_at=NOW() WHERE id=?",
                actor, jobId);
        audit.append(schoolId, actor, "COMMIT", "IMPORT_JOB", jobId, null,
                Map.of("importType", job.type(), "rows", committed), null);
        return new CommitResult(jobId, committed, false);
    }

    private Map<String, Object> read(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Cannot read normalized import row", ex); }
    }

    public record CommitResult(UUID jobId, int committedRows, boolean idempotentReplay) {}
    private record Job(String type, String status, int invalidRows, long version) {}
    private record RowData(UUID id, Map<String, Object> data) {}
}
