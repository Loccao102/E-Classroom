CREATE OR REPLACE FUNCTION audit.capture_sensitive_change() RETURNS trigger AS $$
DECLARE
  sid UUID;
  eid UUID;
BEGIN
  sid := COALESCE(NEW.school_id, OLD.school_id);
  eid := COALESCE(NEW.id, OLD.id);
  INSERT INTO audit.audit_entries(id,school_id,actor_user_id,action,entity_type,entity_id,old_values,new_values,created_at)
  VALUES (
    gen_random_uuid(), sid,
    CASE WHEN TG_TABLE_SCHEMA='attendance' THEN COALESCE(NEW.marked_by,OLD.marked_by) WHEN TG_TABLE_SCHEMA='grading' THEN COALESCE(NEW.recorded_by,OLD.recorded_by) ELSE NULL END,
    TG_OP,
    CASE WHEN TG_TABLE_SCHEMA='attendance' THEN 'ATTENDANCE_RECORD' ELSE 'STUDENT_SCORE' END,
    eid,
    CASE WHEN TG_OP='INSERT' THEN NULL ELSE to_jsonb(OLD) END,
    CASE WHEN TG_OP='DELETE' THEN NULL ELSE to_jsonb(NEW) END,
    NOW()
  );
  RETURN COALESCE(NEW,OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_attendance_record ON attendance.records;
CREATE TRIGGER trg_audit_attendance_record AFTER INSERT OR UPDATE ON attendance.records FOR EACH ROW EXECUTE FUNCTION audit.capture_sensitive_change();
DROP TRIGGER IF EXISTS trg_audit_student_score ON grading.student_scores;
CREATE TRIGGER trg_audit_student_score AFTER INSERT OR UPDATE ON grading.student_scores FOR EACH ROW EXECUTE FUNCTION audit.capture_sensitive_change();
