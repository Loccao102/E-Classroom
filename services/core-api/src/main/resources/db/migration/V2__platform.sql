CREATE TABLE IF NOT EXISTS identity.users (
  id UUID PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  full_name VARCHAR(255) NOT NULL,
  platform_role VARCHAR(32),
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS identity.school_memberships (
  user_id UUID NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
  school_id UUID NOT NULL REFERENCES school.schools(id) ON DELETE CASCADE,
  role VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(user_id, school_id, role)
);
CREATE INDEX IF NOT EXISTS idx_memberships_school_role ON identity.school_memberships(school_id, role, user_id);
CREATE TABLE IF NOT EXISTS identity.refresh_tokens (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS academic.academic_years (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), name VARCHAR(64) NOT NULL,
  start_date DATE NOT NULL, end_date DATE NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'UPCOMING',
  UNIQUE(school_id, name)
);
CREATE TABLE IF NOT EXISTS academic.semesters (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), academic_year_id UUID NOT NULL REFERENCES academic.academic_years(id),
  name VARCHAR(64) NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'UPCOMING',
  UNIQUE(academic_year_id, name)
);
CREATE TABLE IF NOT EXISTS academic.grade_levels (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), name VARCHAR(64) NOT NULL, sort_order INT NOT NULL DEFAULT 0,
  UNIQUE(school_id, name)
);
CREATE TABLE IF NOT EXISTS academic.subjects (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), code VARCHAR(64) NOT NULL, name VARCHAR(255) NOT NULL,
  UNIQUE(school_id, code)
);
CREATE TABLE IF NOT EXISTS academic.teachers (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), user_id UUID REFERENCES identity.users(id),
  teacher_code VARCHAR(64) NOT NULL, full_name VARCHAR(255) NOT NULL, email VARCHAR(255), phone VARCHAR(64), status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE(school_id, teacher_code), UNIQUE(school_id, user_id)
);
CREATE TABLE IF NOT EXISTS academic.students (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), user_id UUID REFERENCES identity.users(id),
  student_code VARCHAR(64) NOT NULL, full_name VARCHAR(255) NOT NULL, date_of_birth DATE, gender VARCHAR(32), status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE(school_id, student_code), UNIQUE(school_id, user_id)
);
CREATE TABLE IF NOT EXISTS academic.guardians (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), user_id UUID REFERENCES identity.users(id),
  full_name VARCHAR(255) NOT NULL, email VARCHAR(255), phone VARCHAR(64), status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE(school_id, user_id)
);
CREATE TABLE IF NOT EXISTS academic.student_guardians (
  school_id UUID NOT NULL REFERENCES school.schools(id), student_id UUID NOT NULL REFERENCES academic.students(id) ON DELETE CASCADE,
  guardian_id UUID NOT NULL REFERENCES academic.guardians(id) ON DELETE CASCADE, relationship VARCHAR(32) NOT NULL,
  primary_contact BOOLEAN NOT NULL DEFAULT FALSE, notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY(student_id, guardian_id)
);
CREATE TABLE IF NOT EXISTS academic.classrooms (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), academic_year_id UUID NOT NULL REFERENCES academic.academic_years(id),
  grade_level_id UUID REFERENCES academic.grade_levels(id), code VARCHAR(64) NOT NULL, name VARCHAR(255) NOT NULL,
  homeroom_teacher_id UUID REFERENCES academic.teachers(id), status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE(school_id, academic_year_id, code)
);
CREATE TABLE IF NOT EXISTS academic.class_enrollments (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), classroom_id UUID NOT NULL REFERENCES academic.classrooms(id),
  student_id UUID NOT NULL REFERENCES academic.students(id), start_date DATE NOT NULL, end_date DATE, status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE(classroom_id, student_id, start_date)
);
CREATE INDEX IF NOT EXISTS idx_enrollment_class_active ON academic.class_enrollments(school_id, classroom_id, status, student_id);
CREATE TABLE IF NOT EXISTS academic.teaching_assignments (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), teacher_id UUID NOT NULL REFERENCES academic.teachers(id),
  classroom_id UUID NOT NULL REFERENCES academic.classrooms(id), subject_id UUID NOT NULL REFERENCES academic.subjects(id),
  semester_id UUID REFERENCES academic.semesters(id), status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE(school_id, teacher_id, classroom_id, subject_id, semester_id)
);
CREATE INDEX IF NOT EXISTS idx_assignment_teacher ON academic.teaching_assignments(school_id, teacher_id, classroom_id, subject_id, status);
CREATE TABLE IF NOT EXISTS academic.timetable_entries (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), teaching_assignment_id UUID NOT NULL REFERENCES academic.teaching_assignments(id),
  weekday INT NOT NULL, period INT NOT NULL, room VARCHAR(64), valid_from DATE, valid_to DATE,
  UNIQUE(school_id, teaching_assignment_id, weekday, period, valid_from)
);

CREATE TABLE IF NOT EXISTS attendance.sessions (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), classroom_id UUID NOT NULL REFERENCES academic.classrooms(id),
  teaching_assignment_id UUID NOT NULL REFERENCES academic.teaching_assignments(id), attendance_date DATE NOT NULL, period INT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'OPEN', version BIGINT NOT NULL DEFAULT 0, created_by UUID NOT NULL REFERENCES identity.users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(school_id, classroom_id, attendance_date, period)
);
CREATE TABLE IF NOT EXISTS attendance.records (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), attendance_session_id UUID NOT NULL REFERENCES attendance.sessions(id) ON DELETE CASCADE,
  student_id UUID NOT NULL REFERENCES academic.students(id), status VARCHAR(32) NOT NULL, note TEXT, marked_by UUID NOT NULL REFERENCES identity.users(id),
  marked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(attendance_session_id, student_id)
);
CREATE INDEX IF NOT EXISTS idx_attendance_student ON attendance.records(school_id, student_id, marked_at DESC);
CREATE TABLE IF NOT EXISTS attendance.leave_requests (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), student_id UUID NOT NULL REFERENCES academic.students(id),
  guardian_user_id UUID NOT NULL REFERENCES identity.users(id), start_date DATE NOT NULL, end_date DATE NOT NULL, reason TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED', reviewed_by UUID REFERENCES identity.users(id), reviewed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS grading.assessments (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), teaching_assignment_id UUID NOT NULL REFERENCES academic.teaching_assignments(id),
  semester_id UUID REFERENCES academic.semesters(id), title VARCHAR(255) NOT NULL, category VARCHAR(64) NOT NULL,
  max_score NUMERIC(8,2) NOT NULL DEFAULT 10, weight NUMERIC(8,2) NOT NULL DEFAULT 1, assessment_date DATE,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT', created_by UUID NOT NULL REFERENCES identity.users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS grading.student_scores (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), assessment_id UUID NOT NULL REFERENCES grading.assessments(id) ON DELETE CASCADE,
  student_id UUID NOT NULL REFERENCES academic.students(id), score NUMERIC(8,2) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  recorded_by UUID NOT NULL REFERENCES identity.users(id), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(assessment_id, student_id)
);
CREATE TABLE IF NOT EXISTS grading.score_revisions (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), student_score_id UUID NOT NULL REFERENCES grading.student_scores(id),
  previous_score NUMERIC(8,2), new_score NUMERIC(8,2) NOT NULL, actor_user_id UUID NOT NULL REFERENCES identity.users(id), reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS communication.announcements (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), title VARCHAR(255) NOT NULL, body TEXT NOT NULL,
  target_type VARCHAR(32) NOT NULL, target_id UUID, published_by UUID NOT NULL REFERENCES identity.users(id), published_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS communication.teacher_comments (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), student_id UUID NOT NULL REFERENCES academic.students(id),
  teacher_user_id UUID NOT NULL REFERENCES identity.users(id), body TEXT NOT NULL, visibility VARCHAR(32) NOT NULL DEFAULT 'GUARDIAN_AND_STUDENT',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS communication.conversations (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), subject VARCHAR(255), created_by UUID NOT NULL REFERENCES identity.users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS communication.conversation_participants (
  conversation_id UUID NOT NULL REFERENCES communication.conversations(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
  PRIMARY KEY(conversation_id, user_id)
);
CREATE TABLE IF NOT EXISTS communication.messages (
  id UUID PRIMARY KEY, conversation_id UUID NOT NULL REFERENCES communication.conversations(id) ON DELETE CASCADE,
  sender_user_id UUID NOT NULL REFERENCES identity.users(id), body TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON communication.messages(conversation_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS notification.notifications (
  id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES school.schools(id), recipient_user_id UUID NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
  type VARCHAR(64) NOT NULL, title VARCHAR(255) NOT NULL, body TEXT NOT NULL, entity_type VARCHAR(64), entity_id UUID,
  read_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notification_feed ON notification.notifications(recipient_user_id, created_at DESC, id DESC);
CREATE TABLE IF NOT EXISTS integration.consumer_inbox (
  consumer_name VARCHAR(128) NOT NULL, event_id UUID NOT NULL, processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(consumer_name, event_id)
);
ALTER TABLE integration.outbox_events ADD COLUMN IF NOT EXISTS correlation_id UUID;
