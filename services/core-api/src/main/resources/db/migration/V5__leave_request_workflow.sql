ALTER TABLE attendance.leave_requests
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_leave_request_student_status_dates
  ON attendance.leave_requests(school_id, student_id, status, start_date, end_date);

ALTER TABLE audit.audit_entries
  ALTER COLUMN correlation_id TYPE VARCHAR(128)
  USING correlation_id::text;

CREATE INDEX IF NOT EXISTS idx_audit_correlation
  ON audit.audit_entries(correlation_id)
  WHERE correlation_id IS NOT NULL;
