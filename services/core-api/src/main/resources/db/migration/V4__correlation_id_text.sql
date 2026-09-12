ALTER TABLE integration.outbox_events
  ALTER COLUMN correlation_id TYPE VARCHAR(128)
  USING correlation_id::text;

CREATE INDEX IF NOT EXISTS idx_outbox_correlation
  ON integration.outbox_events(correlation_id)
  WHERE correlation_id IS NOT NULL;
