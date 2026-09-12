CREATE OR REPLACE FUNCTION audit.capture_attendance_change() RETURNS trigger AS $$
BEGIN
  INSERT INTO audit.audit_entries(
    id, school_id, actor_user_id, action, entity_type, entity_id,
    old_values, new_values, created_at
  )
  VALUES (
    gen_random_uuid(),
    COALESCE(NEW.school_id, OLD.school_id),
    COALESCE(NEW.marked_by, OLD.marked_by),
    TG_OP,
    'ATTENDANCE_RECORD',
    COALESCE(NEW.id, OLD.id),
    CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END,
    CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) END,
    NOW()
  );
  RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION audit.capture_score_change() RETURNS trigger AS $$
BEGIN
  INSERT INTO audit.audit_entries(
    id, school_id, actor_user_id, action, entity_type, entity_id,
    old_values, new_values, created_at
  )
  VALUES (
    gen_random_uuid(),
    COALESCE(NEW.school_id, OLD.school_id),
    COALESCE(NEW.recorded_by, OLD.recorded_by),
    TG_OP,
    'STUDENT_SCORE',
    COALESCE(NEW.id, OLD.id),
    CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END,
    CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) END,
    NOW()
  );
  RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_attendance_record ON attendance.records;
CREATE TRIGGER trg_audit_attendance_record
AFTER INSERT OR UPDATE ON attendance.records
FOR EACH ROW EXECUTE FUNCTION audit.capture_attendance_change();

DROP TRIGGER IF EXISTS trg_audit_student_score ON grading.student_scores;
CREATE TRIGGER trg_audit_student_score
AFTER INSERT OR UPDATE ON grading.student_scores
FOR EACH ROW EXECUTE FUNCTION audit.capture_score_change();
