ALTER TABLE grading.assessments
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS submitted_by UUID REFERENCES identity.users(id),
  ADD COLUMN IF NOT EXISTS locked_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS locked_by UUID REFERENCES identity.users(id);

ALTER TABLE grading.score_revisions
  ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_assessments_assignment_status
  ON grading.assessments(school_id, teaching_assignment_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_score_revisions_assessment_cursor
  ON grading.score_revisions(school_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_score_revisions_score_cursor
  ON grading.score_revisions(student_score_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_score_revisions_correlation
  ON grading.score_revisions(correlation_id)
  WHERE correlation_id IS NOT NULL;
