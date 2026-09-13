CREATE TABLE communication.parent_meetings (
    id UUID PRIMARY KEY,
    school_id UUID NOT NULL REFERENCES school.schools(id),
    scope_type VARCHAR(20) NOT NULL,
    scope_id UUID,
    title VARCHAR(255) NOT NULL,
    agenda TEXT NOT NULL,
    note TEXT,
    location VARCHAR(255) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    include_students BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    created_by UUID NOT NULL REFERENCES identity.users(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_parent_meeting_scope CHECK (scope_type IN ('SCHOOL','CLASSROOM','STUDENT')),
    CONSTRAINT chk_parent_meeting_scope_id CHECK (
        (scope_type='SCHOOL' AND scope_id IS NULL) OR
        (scope_type IN ('CLASSROOM','STUDENT') AND scope_id IS NOT NULL)
    ),
    CONSTRAINT chk_parent_meeting_status CHECK (status IN ('SCHEDULED','CANCELLED','COMPLETED')),
    CONSTRAINT chk_parent_meeting_time CHECK (ends_at > starts_at)
);

CREATE INDEX idx_parent_meetings_school_time
    ON communication.parent_meetings(school_id, starts_at DESC, id DESC);
CREATE INDEX idx_parent_meetings_scope
    ON communication.parent_meetings(school_id, scope_type, scope_id, starts_at DESC);

CREATE TABLE communication.meeting_invitees (
    id UUID PRIMARY KEY,
    school_id UUID NOT NULL REFERENCES school.schools(id),
    meeting_id UUID NOT NULL REFERENCES communication.parent_meetings(id) ON DELETE CASCADE,
    guardian_user_id UUID NOT NULL REFERENCES identity.users(id),
    student_id UUID NOT NULL REFERENCES academic.students(id),
    response VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    responded_at TIMESTAMPTZ,
    attendance VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    attendance_recorded_by UUID REFERENCES identity.users(id),
    attendance_recorded_at TIMESTAMPTZ,
    last_reminded_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_meeting_invitee UNIQUE(meeting_id, guardian_user_id, student_id),
    CONSTRAINT chk_meeting_response CHECK (response IN ('PENDING','ACCEPTED','DECLINED')),
    CONSTRAINT chk_meeting_attendance CHECK (attendance IN ('UNKNOWN','PRESENT','ABSENT'))
);

CREATE INDEX idx_meeting_invitees_guardian
    ON communication.meeting_invitees(school_id, guardian_user_id, meeting_id);
CREATE INDEX idx_meeting_invitees_meeting
    ON communication.meeting_invitees(meeting_id, response, attendance);

CREATE TABLE communication.meeting_slots (
    id UUID PRIMARY KEY,
    school_id UUID NOT NULL REFERENCES school.schools(id),
    meeting_id UUID NOT NULL REFERENCES communication.parent_meetings(id) ON DELETE CASCADE,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    booked_by_guardian_user_id UUID REFERENCES identity.users(id),
    booked_for_student_id UUID REFERENCES academic.students(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_meeting_slot UNIQUE(meeting_id, starts_at, ends_at),
    CONSTRAINT chk_meeting_slot_time CHECK (ends_at > starts_at),
    CONSTRAINT chk_meeting_slot_booking_pair CHECK (
        (booked_by_guardian_user_id IS NULL AND booked_for_student_id IS NULL) OR
        (booked_by_guardian_user_id IS NOT NULL AND booked_for_student_id IS NOT NULL)
    )
);

CREATE INDEX idx_meeting_slots_meeting_time
    ON communication.meeting_slots(meeting_id, starts_at, id);
CREATE UNIQUE INDEX uq_meeting_slot_guardian_student
    ON communication.meeting_slots(meeting_id, booked_by_guardian_user_id, booked_for_student_id)
    WHERE booked_by_guardian_user_id IS NOT NULL;

CREATE TABLE communication.meeting_outcomes (
    id UUID PRIMARY KEY,
    school_id UUID NOT NULL REFERENCES school.schools(id),
    meeting_id UUID NOT NULL REFERENCES communication.parent_meetings(id) ON DELETE CASCADE,
    student_id UUID REFERENCES academic.students(id),
    body TEXT NOT NULL,
    visibility VARCHAR(40) NOT NULL,
    recorded_by UUID NOT NULL REFERENCES identity.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_meeting_outcome_visibility CHECK (
        visibility IN ('STAFF_ONLY','GUARDIAN','STUDENT_AND_GUARDIAN')
    ),
    CONSTRAINT chk_meeting_outcome_student CHECK (
        visibility='STAFF_ONLY' OR student_id IS NOT NULL
    )
);

CREATE INDEX idx_meeting_outcomes_meeting
    ON communication.meeting_outcomes(meeting_id, created_at DESC, id DESC);
