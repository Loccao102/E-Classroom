package com.eclassroom.core.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class BulkImportRowRepository {
    private final JdbcTemplate jdbc;

    public BulkImportRowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(UUID jobId, List<StagedRow> rows) {
        jdbc.update("DELETE FROM integration.import_rows WHERE job_id=?", jobId);
        if (rows.isEmpty()) return;
        List<Object[]> batch = rows.stream().map(row -> new Object[]{
                row.id(), row.schoolId(), jobId, row.rowNumber(), row.rawJson(), row.normalizedJson(),
                row.status(), row.errorsJson(), row.warningsJson()
        }).toList();
        jdbc.batchUpdate(
                "INSERT INTO integration.import_rows(id,school_id,job_id,row_number,raw_data,normalized_data,status,errors,warnings) " +
                        "VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb))",
                batch);
    }

    public record StagedRow(UUID id, UUID schoolId, int rowNumber, String rawJson, String normalizedJson,
                            String status, String errorsJson, String warningsJson) {}
}
