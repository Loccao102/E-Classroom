package com.eclassroom.core.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
class PeopleImportValidator {
    private final JdbcTemplate jdbc;
    PeopleImportValidator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    ImportValidationService.ValidationResult validate(UUID schoolId, String type, Map<String,String> raw, Set<String> seen) {
        Map<String,String> normalized = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        if ("STUDENT".equals(type)) student(schoolId, raw, normalized, errors, warnings, seen);
        else if ("TEACHER".equals(type)) teacher(schoolId, raw, normalized, errors, warnings, seen);
        else guardian(schoolId, raw, normalized, errors, warnings, seen);
        return new ImportValidationService.ValidationResult(normalized, errors, warnings);
    }

    private void student(UUID schoolId, Map<String,String> raw, Map<String,String> n, List<String> e, List<String> w, Set<String> seen) {
        String code = upper(required(raw,"student_code",e)), name = required(raw,"full_name",e);
        String dob = date(raw.get("date_of_birth"), e), gender = upper(raw.get("gender")), email = email(raw.get("email"), e);
        n.put("student_code",code); n.put("full_name",name); n.put("date_of_birth",dob); n.put("gender",gender); n.put("email",email);
        duplicate("student:"+code,seen,e); if (code.isBlank()) return;
        List<Map<String,Object>> current = jdbc.queryForList("SELECT id,user_id FROM academic.students WHERE school_id=? AND upper(student_code)=?", schoolId, code);
        existing(current,email,e,w);
    }

    private void teacher(UUID schoolId, Map<String,String> raw, Map<String,String> n, List<String> e, List<String> w, Set<String> seen) {
        String code = upper(required(raw,"teacher_code",e)), name = required(raw,"full_name",e), email = email(raw.get("email"),e), phone = clean(raw.get("phone"));
        n.put("teacher_code",code); n.put("full_name",name); n.put("email",email); n.put("phone",phone);
        duplicate("teacher:"+code,seen,e); if (code.isBlank()) return;
        List<Map<String,Object>> current = jdbc.queryForList("SELECT id,user_id FROM academic.teachers WHERE school_id=? AND upper(teacher_code)=?", schoolId, code);
        existing(current,email,e,w);
    }

    private void guardian(UUID schoolId, Map<String,String> raw, Map<String,String> n, List<String> e, List<String> w, Set<String> seen) {
        String name = required(raw,"full_name",e), email = email(raw.get("email"),e), phone = clean(raw.get("phone"));
        if (email.isBlank() && phone.isBlank()) e.add("GUARDIAN_EMAIL_OR_PHONE_REQUIRED");
        String key = !email.isBlank() ? "email:"+email : "phone:"+phone;
        n.put("full_name",name); n.put("email",email); n.put("phone",phone); n.put("natural_key",key);
        duplicate("guardian:"+key,seen,e);
        List<Map<String,Object>> current = jdbc.queryForList("SELECT id,user_id FROM academic.guardians WHERE school_id=? AND ((?<>'' AND lower(email)=lower(?)) OR (?<>'' AND phone=?))", schoolId,email,email,phone,phone);
        if (current.size()>1) e.add("GUARDIAN_REFERENCE_AMBIGUOUS"); else existing(current,email,e,w);
    }

    private void existing(List<Map<String,Object>> current,String email,List<String> errors,List<String> warnings) {
        Object allowed = current.isEmpty() ? null : current.getFirst().get("user_id");
        if (!current.isEmpty()) warnings.add("EXISTING_RECORD_WILL_UPDATE");
        if (email.isBlank()) return;
        List<Map<String,Object>> users = jdbc.queryForList("SELECT id FROM identity.users WHERE lower(email)=lower(?)", email);
        if (!users.isEmpty() && (allowed==null || !users.getFirst().get("id").equals(allowed))) errors.add("EMAIL_ALREADY_USED");
    }

    private String required(Map<String,String> raw,String key,List<String> errors) { String v=clean(raw.get(key)); if(v.isBlank()) errors.add(key.toUpperCase(Locale.ROOT)+"_REQUIRED"); return v; }
    private String email(String value,List<String> errors) { String v=clean(value).toLowerCase(Locale.ROOT); if(!v.isBlank() && (!v.contains("@") || v.startsWith("@") || v.endsWith("@"))) errors.add("EMAIL_INVALID"); return v; }
    private String date(String value,List<String> errors) { String v=clean(value); if(v.isBlank()) return ""; try{return LocalDate.parse(v).toString();}catch(Exception ex){errors.add("DATE_OF_BIRTH_INVALID");return v;} }
    private void duplicate(String key,Set<String> seen,List<String> errors) { if(!seen.add(key)) errors.add("DUPLICATE_ROW_KEY"); }
    private String clean(String value) { return value==null?"":value.trim(); }
    private String upper(String value) { return clean(value).toUpperCase(Locale.ROOT); }
}
