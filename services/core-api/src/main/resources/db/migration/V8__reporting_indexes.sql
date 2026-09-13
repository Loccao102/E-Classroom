-- Reporting hot-path indexes. These keep transactional tables authoritative while
-- making bounded, tenant/date-scoped dashboards cheap enough for V1.

CREATE INDEX IF NOT EXISTS idx_attendance_sessions_reporting
  ON attendance.sessions(school_id, attendance_date DESC, classroom_id, teaching_assignment_id, status);

CREATE INDEX IF NOT EXISTS idx_attendance_records_reporting
  ON attendance.records(school_id, status, student_id, attendance_session_id);

CREATE INDEX IF NOT EXISTS idx_assessments_reporting
  ON grading.assessments(school_id, status, assessment_date DESC, semester_id, teaching_assignment_id);

CREATE INDEX IF NOT EXISTS idx_scores_reporting
  ON grading.student_scores(school_id, status, student_id, assessment_id);

CREATE INDEX IF NOT EXISTS idx_leave_requests_reporting
  ON attendance.leave_requests(school_id, status, start_date, end_date, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_teacher_comments_timeline
  ON communication.teacher_comments(school_id, student_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_timetable_reporting
  ON academic.timetable_entries(school_id, weekday, teaching_assignment_id, valid_from, valid_to);

CREATE INDEX IF NOT EXISTS idx_enrollment_student_active
  ON academic.class_enrollments(school_id, student_id, status, classroom_id);
