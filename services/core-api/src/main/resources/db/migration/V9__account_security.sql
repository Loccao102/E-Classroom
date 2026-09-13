ALTER TABLE identity.users
  ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMPTZ;

ALTER TABLE identity.refresh_tokens
  ADD COLUMN IF NOT EXISTS session_id UUID,
  ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS user_agent VARCHAR(255),
  ADD COLUMN IF NOT EXISTS ip_hash VARCHAR(64),
  ADD COLUMN IF NOT EXISTS revoked_reason VARCHAR(64);

UPDATE identity.refresh_tokens
SET session_id = id
WHERE session_id IS NULL;

UPDATE identity.refresh_tokens
SET last_seen_at = created_at
WHERE last_seen_at IS NULL;

ALTER TABLE identity.refresh_tokens
  ALTER COLUMN session_id SET NOT NULL,
  ALTER COLUMN last_seen_at SET NOT NULL,
  ALTER COLUMN last_seen_at SET DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_refresh_user_sessions
  ON identity.refresh_tokens(user_id, session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_refresh_active_session
  ON identity.refresh_tokens(user_id, session_id, expires_at DESC)
  WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS identity.login_attempts (
  identifier_hash VARCHAR(64) PRIMARY KEY,
  failed_count INTEGER NOT NULL DEFAULT 0,
  window_started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  locked_until TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_login_attempt_lock_cleanup
  ON identity.login_attempts(locked_until, updated_at);

CREATE TABLE IF NOT EXISTS identity.security_events (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES identity.users(id) ON DELETE SET NULL,
  school_id UUID REFERENCES school.schools(id) ON DELETE SET NULL,
  event_type VARCHAR(96) NOT NULL,
  outcome VARCHAR(32) NOT NULL,
  session_id UUID,
  correlation_id VARCHAR(128),
  metadata JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_security_events_user_time
  ON identity.security_events(user_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_security_events_school_time
  ON identity.security_events(school_id, created_at DESC, id DESC)
  WHERE school_id IS NOT NULL;
