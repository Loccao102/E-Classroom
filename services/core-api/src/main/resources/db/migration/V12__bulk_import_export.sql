CREATE TABLE integration.import_jobs (
    id UUID PRIMARY KEY,
    school_id UUID NOT NULL REFERENCES school.schools(id),
    import_type VARCHAR(32) NOT NULL,
    source_format VARCHAR(16) NOT NULL,
    source_file_name VARCHAR(255) NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'STAGED',
    source_blob BYTEA,
    total_rows INTEGER NOT NULL DEFAULT 0,
    valid_rows INTEGER NOT NULL DEFAULT 0,
    invalid_rows INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_by UUID NOT NULL REFERENCES identity.users(id),
    committed_by UUID REFERENCES identity.users(id),
    committed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_import_job_idempotency UNIQUE (school_id, idempotency_key),
    CONSTRAINT chk_import_type CHECK (import_type IN ('STUDENT','TEACHER','GUARDIAN','GUARDIAN_LINK','ENROLLMENT')),
    CONSTRAINT chk_import_format CHECK (source_format IN ('CSV','XLSX')),
    CONSTRAINT chk_import_status CHECK (status IN (
        'STAGED','QUEUED','PROCESSING','PREVIEW_READY','VALIDATION_FAILED','COMMITTING','COMMITTED','FAILED'
    )),
    CONSTRAINT chk_import_counts CHECK (total_rows >= 0 AND valid_rows >= 0 AND invalid_rows >= 0)
);

CREATE INDEX idx_import_jobs_school_created
    ON integration.import_jobs(school_id, created_at DESC, id DESC);
CREATE INDEX idx_import_jobs_queue
    ON integration.import_jobs(status, created_at)
    WHERE status IN ('QUEUED','PROCESSING');

CREATE TABLE integration.import_rows (
    id UUID PRIMARY KEY,
    school_id UUID NOT NULL REFERENCES school.schools(id),
    job_id UUID NOT NULL REFERENCES integration.import_jobs(id) ON DELETE CASCADE,
    row_number INTEGER NOT NULL,
    raw_data JSONB NOT NULL,
    normalized_data JSONB,
    status VARCHAR(16) NOT NULL,
    errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    committed_entity_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_import_row_number UNIQUE(job_id, row_number),
    CONSTRAINT chk_import_row_status CHECK (status IN ('VALID','INVALID','COMMITTED')),
    CONSTRAINT chk_import_row_number CHECK (row_number > 0)
);

CREATE INDEX idx_import_rows_preview
    ON integration.import_rows(job_id, status, row_number);

CREATE TABLE integration.export_audit (
    id UUID PRIMARY KEY,
    school_id UUID NOT NULL REFERENCES school.schools(id),
    export_type VARCHAR(32) NOT NULL,
    scope_type VARCHAR(32),
    scope_id UUID,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    format VARCHAR(16) NOT NULL,
    row_count INTEGER NOT NULL DEFAULT 0,
    exported_by UUID NOT NULL REFERENCES identity.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_export_type CHECK (export_type IN ('ROSTER','ATTENDANCE','SCORES','STUDENTS','TEACHERS','GUARDIANS')),
    CONSTRAINT chk_export_format CHECK (format IN ('CSV','XLSX'))
);

CREATE INDEX idx_export_audit_school_created
    ON integration.export_audit(school_id, created_at DESC, id DESC);
