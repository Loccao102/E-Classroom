package com.eclassroom.core.academic;

import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BulkAcademicImportWriter {
    private static final Set<String> BATCH_TYPES = Set.of("STUDENT", "TEACHER");
    private final JdbcTemplate jdbc;

    public BulkAcademicImportWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean supportsBatch(String type) { return BATCH_TYPES.contains(type); }

    public void applyBatch(UUID schoolId, String type, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        switch (type) {
            case "STUDENT" -> students(schoolId, rows);
            case "TEACHER" -> teachers(schoolId, rows);
            default -> throw ApiException.badRequest("INVALID_IMPORT_TYPE", "Import type is not batch-enabled");
        }
    }

    public UUID apply(UUID schoolId, String type, Map<String, Object> data) {
        return switch (type) {
            case "STUDENT" -> student(schoolId, data);
            case "TEACHER" -> teacher(schoolId, data);
            case "GUARDIAN" -> guardian(schoolId, data);
            case "GUARDIAN_LINK" -> link(schoolId, data);
            case "ENROLLMENT" -> enrollment(schoolId, data);
            default -> throw ApiException.badRequest("INVALID_IMPORT_TYPE", "Unsupported import type");
        };
    }

    private void students(UUID schoolId, List<Map<String, Object>> rows) {
        List<Object[]> batch = rows.stream().map(d -> new Object[]{
                UUID.randomUUID(), schoolId, str(d, "student_code"), str(d, "full_name"),
                date(d, "date_of_birth"), nullable(d, "gender"), nullable(d, "email")
        }).toList();
        jdbc.batchUpdate(
                "INSERT INTO academic.students(id,school_id,student_code,full_name,date_of_birth,gender,email,status) " +
                        "VALUES (?,?,?,?,?,?,?,'ACTIVE') " +
                        "ON CONFLICT (school_id,student_code) DO UPDATE SET " +
                        "full_name=EXCLUDED.full_name,date_of_birth=EXCLUDED.date_of_birth,gender=EXCLUDED.gender,email=EXCLUDED.email,status='ACTIVE'",
                batch);
    }

    private void teachers(UUID schoolId, List<Map<String, Object>> rows) {
        List<Object[]> batch = rows.stream().map(d -> new Object[]{
                UUID.randomUUID(), schoolId, str(d, "teacher_code"), str(d, "full_name"),
                nullable(d, "email"), nullable(d, "phone")
        }).toList();
        jdbc.batchUpdate(
                "INSERT INTO academic.teachers(id,school_id,teacher_code,full_name,email,phone,status) " +
                        "VALUES (?,?,?,?,?,?,'ACTIVE') " +
                        "ON CONFLICT (school_id,teacher_code) DO UPDATE SET " +
                        "full_name=EXCLUDED.full_name,email=EXCLUDED.email,phone=EXCLUDED.phone,status='ACTIVE'",
                batch);
    }

    private UUID student(UUID schoolId, Map<String, Object> d) {
        String code = str(d, "student_code"), name = str(d, "full_name"), email = nullable(d, "email"), gender = nullable(d, "gender");
        LocalDate dob = date(d, "date_of_birth");
        UUID id = first("SELECT id FROM academic.students WHERE school_id=? AND student_code=?", schoolId, code);
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.update("INSERT INTO academic.students(id,school_id,student_code,full_name,date_of_birth,gender,email,status) VALUES (?,?,?,?,?,?,?,'ACTIVE')", id, schoolId, code, name, dob, gender, email);
        } else jdbc.update("UPDATE academic.students SET full_name=?,date_of_birth=?,gender=?,email=?,status='ACTIVE' WHERE id=?", name, dob, gender, email, id);
        return id;
    }

    private UUID teacher(UUID schoolId, Map<String, Object> d) {
        String code = str(d, "teacher_code"), name = str(d, "full_name"), email = nullable(d, "email"), phone = nullable(d, "phone");
        UUID id = first("SELECT id FROM academic.teachers WHERE school_id=? AND teacher_code=?", schoolId, code);
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.update("INSERT INTO academic.teachers(id,school_id,teacher_code,full_name,email,phone,status) VALUES (?,?,?,?,?,?,'ACTIVE')", id, schoolId, code, name, email, phone);
        } else jdbc.update("UPDATE academic.teachers SET full_name=?,email=?,phone=?,status='ACTIVE' WHERE id=?", name, email, phone, id);
        return id;
    }

    private UUID guardian(UUID schoolId, Map<String, Object> d) {
        String email = str(d, "guardian_email"), name = str(d, "full_name"), phone = nullable(d, "phone");
        UUID id = first("SELECT id FROM academic.guardians WHERE school_id=? AND lower(email)=lower(?)", schoolId, email);
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.update("INSERT INTO academic.guardians(id,school_id,full_name,email,phone,status) VALUES (?,?,?,?,?,'ACTIVE')", id, schoolId, name, email, phone);
        } else jdbc.update("UPDATE academic.guardians SET full_name=?,phone=?,status='ACTIVE' WHERE id=?", name, phone, id);
        return id;
    }

    private UUID link(UUID schoolId, Map<String, Object> d) {
        UUID student = first("SELECT id FROM academic.students WHERE school_id=? AND student_code=?", schoolId, str(d, "student_code"));
        UUID guardian = first("SELECT id FROM academic.guardians WHERE school_id=? AND lower(email)=lower(?)", schoolId, str(d, "guardian_email"));
        if (student == null || guardian == null) throw ApiException.badRequest("IMPORT_REFERENCE_MISSING", "Student or guardian reference no longer exists");
        jdbc.update("INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact,notifications_enabled) VALUES (?,?,?,?,?,TRUE) ON CONFLICT(student_id,guardian_id) DO UPDATE SET relationship=EXCLUDED.relationship,primary_contact=EXCLUDED.primary_contact", schoolId, student, guardian, str(d, "relationship"), Boolean.TRUE.equals(d.get("primary_contact")));
        return student;
    }

    private UUID enrollment(UUID schoolId, Map<String, Object> d) {
        UUID student = first("SELECT id FROM academic.students WHERE school_id=? AND student_code=?", schoolId, str(d, "student_code"));
        UUID classroom = first("SELECT c.id FROM academic.classrooms c JOIN academic.academic_years y ON y.id=c.academic_year_id WHERE c.school_id=? AND c.code=? AND y.name=?", schoolId, str(d, "classroom_code"), str(d, "academic_year"));
        if (student == null || classroom == null) throw ApiException.badRequest("IMPORT_REFERENCE_MISSING", "Student or classroom reference no longer exists");
        UUID existing = first("SELECT id FROM academic.class_enrollments WHERE school_id=? AND classroom_id=? AND student_id=? AND status='ACTIVE'", schoolId, classroom, student);
        if (existing != null) return existing;
        Integer other = jdbc.queryForObject("SELECT COUNT(*) FROM academic.class_enrollments e JOIN academic.classrooms c ON c.id=e.classroom_id WHERE e.school_id=? AND e.student_id=? AND e.status='ACTIVE' AND c.academic_year_id=(SELECT academic_year_id FROM academic.classrooms WHERE id=?)", Integer.class, schoolId, student, classroom);
        if (other != null && other > 0) throw ApiException.conflict("ACTIVE_ENROLLMENT_EXISTS", "Student already has an active enrollment in this academic year");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')", id, schoolId, classroom, student, date(d, "start_date"));
        return id;
    }

    private UUID first(String sql, Object... args) {
        List<UUID> ids = jdbc.query(sql, (rs, i) -> rs.getObject(1, UUID.class), args);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private String str(Map<String, Object> d, String key) { Object v = d.get(key); return v == null ? "" : String.valueOf(v); }
    private String nullable(Map<String, Object> d, String key) { String v = str(d, key); return v.isBlank() ? null : v; }
    private LocalDate date(Map<String, Object> d, String key) { String v = nullable(d, key); return v == null ? null : LocalDate.parse(v); }
}
