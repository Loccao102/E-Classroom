CREATE SCHEMA IF NOT EXISTS conduct;

CREATE TABLE conduct.records (
    id UUID PRIMARY KEY,
    school_id UUID NOT NULL REFERENCES school.schools(id),
    student_id UUID NOT NULL REFERENCES academic.students(id),
    category VARCHAR(40) NOT NULL,
    severity VARCHAR(20),
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    visibility VARCHAR(40) NOT NULL,
    classroom_id UUID REFERENCES academic.classrooms(id),
    subject_id UUID REFERENCES academic.subjects(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL REFERENCES identity.users(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_conduct_category CHECK (category IN ('POSITIVE_RECOGNITION','REMINDER','VIOLATION','ACHIEVEMENT','GENERAL')),
    CONSTRAINT chk_conduct_severity CHECK (severity IS NULL OR severity IN ('INFO','LOW','MEDIUM','HIGH')),
    CONSTRAINT chk_conduct_visibility CHECK (visibility IN ('STAFF_ONLY','GUARDIAN','STUDENT','STUDENT_AND_GUARDIAN'))
);

CREATE INDEX idx_conduct_student_timeline
    ON conduct.records(school_id, student_id, occurred_at DESC, id DESC);
CREATE INDEX idx_conduct_context
    ON conduct.records(school_id, classroom_id, subject_id, occurred_at DESC);

CREATE TABLE conduct.revisions (
    id UUID PRIMARY KEY,
    school_id UUID NOT NULL REFERENCES school.schools(id),
    conduct_record_id UUID NOT NULL REFERENCES conduct.records(id) ON DELETE CASCADE,
    version_before BIGINT NOT NULL,
    version_after BIGINT NOT NULL,
    previous_values JSONB NOT NULL,
    new_values JSONB NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES identity.users(id),
    reason TEXT NOT NULL,
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conduct_revision_history
    ON conduct.revisions(school_id, conduct_record_id, created_at DESC, id DESC);
CREATE INDEX idx_conduct_revision_correlation
    ON conduct.revisions(correlation_id) WHERE correlation_id IS NOT NULL;

-- Timeline reads use one bounded UNION read model. These indexes keep the source scans tenant/student scoped.
CREATE INDEX IF NOT EXISTS idx_leave_student_timeline
    ON attendance.leave_requests(school_id, student_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_score_student_timeline
    ON grading.student_scores(school_id, student_id, updated_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_comment_student_timeline
    ON communication.teacher_comments(school_id, student_id, created_at DESC, id DESC);
