package com.eclassroom.core.integration;

import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class BulkImportValidator {
    public static final Set<String> TYPES = Set.of("STUDENT", "TEACHER", "GUARDIAN", "GUARDIAN_LINK", "ENROLLMENT");
    private static final int LOOKUP_CHUNK = 500;
    private final JdbcTemplate jdbc;

    public BulkImportValidator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String type(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(result)) throw ApiException.badRequest("INVALID_IMPORT_TYPE", "Unsupported import type");
        return result;
    }

    public List<String> headers(String value) {
        return switch (type(value)) {
            case "STUDENT" -> List.of("student_code", "full_name", "date_of_birth", "gender", "email");
            case "TEACHER" -> List.of("teacher_code", "full_name", "email", "phone");
            case "GUARDIAN" -> List.of("guardian_email", "full_name", "phone");
            case "GUARDIAN_LINK" -> List.of("student_code", "guardian_email", "relationship", "primary_contact");
            case "ENROLLMENT" -> List.of("student_code", "classroom_code", "academic_year", "start_date");
            default -> throw ApiException.badRequest("INVALID_IMPORT_TYPE", "Unsupported import type");
        };
    }

    public BatchContext prepare(UUID schoolId, String importType, List<BulkImportParser.RawRow> rows) {
        String normalizedType = type(importType);
        if (!Set.of("STUDENT", "TEACHER").contains(normalizedType)) return BatchContext.empty();

        String codeField = "STUDENT".equals(normalizedType) ? "student_code" : "teacher_code";
        Set<String> codes = new HashSet<>();
        Set<String> emails = new HashSet<>();
        for (BulkImportParser.RawRow row : rows) {
            String code = opt(row.values().get(codeField));
            if (code != null) codes.add(code.toUpperCase(Locale.ROOT));
            String mail = opt(row.values().get("email"));
            if (mail != null) emails.add(mail.toLowerCase(Locale.ROOT));
        }

        Set<String> existingCodes = new HashSet<>();
        Map<String, UUID> ownUsers = new HashMap<>();
        String table = "STUDENT".equals(normalizedType) ? "academic.students" : "academic.teachers";
        String column = "STUDENT".equals(normalizedType) ? "student_code" : "teacher_code";
        for (List<String> chunk : chunks(codes)) {
            List<Object> args = new ArrayList<>();
            args.add(schoolId);
            args.addAll(chunk);
            jdbc.query("SELECT " + column + ",user_id FROM " + table + " WHERE school_id=? AND " + column + " IN (" + placeholders(chunk.size()) + ")",
                    rs -> {
                        String key = rs.getString(1).toUpperCase(Locale.ROOT);
                        existingCodes.add(key);
                        UUID userId = rs.getObject(2, UUID.class);
                        if (userId != null) ownUsers.put(key, userId);
                    }, args.toArray());
        }

        Map<String, UUID> emailUsers = new HashMap<>();
        for (List<String> chunk : chunks(emails)) {
            jdbc.query("SELECT lower(email),id FROM identity.users WHERE lower(email) IN (" + placeholders(chunk.size()) + ")",
                    rs -> emailUsers.put(rs.getString(1), rs.getObject(2, UUID.class)), chunk.toArray());
        }
        return new BatchContext(existingCodes, ownUsers, emailUsers);
    }

    public Validation validate(UUID schoolId, String importType, Map<String,String> source) {
        return validate(schoolId, importType, source, null);
    }

    public Validation validate(UUID schoolId, String importType, Map<String,String> source, BatchContext context) {
        Map<String,Object> n = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        switch (type(importType)) {
            case "STUDENT" -> student(schoolId, source, n, errors, warnings, context);
            case "TEACHER" -> teacher(schoolId, source, n, errors, warnings, context);
            case "GUARDIAN" -> guardian(schoolId, source, n, errors, warnings);
            case "GUARDIAN_LINK" -> guardianLink(schoolId, source, n, errors);
            case "ENROLLMENT" -> enrollment(schoolId, source, n, errors, warnings);
        }
        return new Validation(n, errors, warnings);
    }

    private void student(UUID schoolId, Map<String,String> s, Map<String,Object> n, List<String> e, List<String> w, BatchContext context) {
        String code = req(s,"student_code",e).toUpperCase(Locale.ROOT), name=req(s,"full_name",e), mail=email(s.get("email"),e);
        LocalDate dob=date(s.get("date_of_birth"),"date_of_birth",e,false); String gender=opt(s.get("gender"));
        n.put("student_code",code); n.put("full_name",name); n.put("date_of_birth",dob==null?null:dob.toString()); n.put("gender",gender); n.put("email",mail);
        if(mail==null)w.add("No email: profile will not receive a login account");
        if(context != null) {
            if(!code.isBlank()&&context.existingCodes().contains(code))w.add("Existing student code will be updated");
            if(mail!=null&&emailUsedByOtherProfile(context,code,mail))e.add("Email already belongs to a different account/profile");
        } else {
            if(!code.isBlank()&&count("SELECT COUNT(*) FROM academic.students WHERE school_id=? AND student_code=?",schoolId,code)>0)w.add("Existing student code will be updated");
            if(mail!=null&&emailUsedByOtherProfile(schoolId,"academic.students","student_code",code,mail))e.add("Email already belongs to a different account/profile");
        }
    }

    private void teacher(UUID schoolId, Map<String,String> s, Map<String,Object> n, List<String> e, List<String> w, BatchContext context) {
        String code=req(s,"teacher_code",e).toUpperCase(Locale.ROOT), name=req(s,"full_name",e), mail=email(s.get("email"),e), phone=opt(s.get("phone"));
        n.put("teacher_code",code); n.put("full_name",name); n.put("email",mail); n.put("phone",phone);
        if(mail==null)w.add("No email: profile will not receive a login account");
        if(context != null) {
            if(!code.isBlank()&&context.existingCodes().contains(code))w.add("Existing teacher code will be updated");
            if(mail!=null&&emailUsedByOtherProfile(context,code,mail))e.add("Email already belongs to a different account/profile");
        } else {
            if(!code.isBlank()&&count("SELECT COUNT(*) FROM academic.teachers WHERE school_id=? AND teacher_code=?",schoolId,code)>0)w.add("Existing teacher code will be updated");
            if(mail!=null&&emailUsedByOtherProfile(schoolId,"academic.teachers","teacher_code",code,mail))e.add("Email already belongs to a different account/profile");
        }
    }

    private void guardian(UUID schoolId, Map<String,String> s, Map<String,Object> n, List<String> e, List<String> w) {
        String mail=email(req(s,"guardian_email",e),e), name=req(s,"full_name",e), phone=opt(s.get("phone"));
        n.put("guardian_email",mail); n.put("full_name",name); n.put("phone",phone);
        if(mail!=null&&count("SELECT COUNT(*) FROM academic.guardians WHERE school_id=? AND lower(email)=lower(?)",schoolId,mail)>0)w.add("Existing guardian email will be updated");
        if(mail!=null&&count("SELECT COUNT(*) FROM identity.users u WHERE lower(u.email)=lower(?) AND NOT EXISTS (SELECT 1 FROM identity.school_memberships m WHERE m.user_id=u.id AND m.school_id=?)",mail,schoolId)>0)e.add("Email already belongs to an account outside this school");
    }

    private void guardianLink(UUID schoolId, Map<String,String> s, Map<String,Object> n, List<String> e) {
        String student=req(s,"student_code",e).toUpperCase(Locale.ROOT), mail=email(req(s,"guardian_email",e),e), relation=req(s,"relationship",e).toUpperCase(Locale.ROOT);
        boolean primary=bool(s.get("primary_contact"),"primary_contact",e);
        n.put("student_code",student); n.put("guardian_email",mail); n.put("relationship",relation); n.put("primary_contact",primary);
        if(!student.isBlank()&&count("SELECT COUNT(*) FROM academic.students WHERE school_id=? AND student_code=? AND status='ACTIVE'",schoolId,student)==0)e.add("Student code does not exist or is inactive");
        if(mail!=null&&count("SELECT COUNT(*) FROM academic.guardians WHERE school_id=? AND lower(email)=lower(?) AND status='ACTIVE'",schoolId,mail)==0)e.add("Guardian email does not exist or is inactive");
    }

    private void enrollment(UUID schoolId, Map<String,String> s, Map<String,Object> n, List<String> e, List<String> w) {
        String student=req(s,"student_code",e).toUpperCase(Locale.ROOT), classroom=req(s,"classroom_code",e).toUpperCase(Locale.ROOT), year=req(s,"academic_year",e);
        LocalDate start=date(req(s,"start_date",e),"start_date",e,true);
        n.put("student_code",student); n.put("classroom_code",classroom); n.put("academic_year",year); n.put("start_date",start==null?null:start.toString());
        if(!student.isBlank()&&count("SELECT COUNT(*) FROM academic.students WHERE school_id=? AND student_code=? AND status='ACTIVE'",schoolId,student)==0)e.add("Student code does not exist or is inactive");
        if(!classroom.isBlank()&&!year.isBlank()&&count("SELECT COUNT(*) FROM academic.classrooms c JOIN academic.academic_years y ON y.id=c.academic_year_id WHERE c.school_id=? AND c.code=? AND y.name=? AND c.status='ACTIVE'",schoolId,classroom,year)==0)e.add("Classroom code / academic year does not exist");
        if(e.isEmpty()){
            if(count("SELECT COUNT(*) FROM academic.class_enrollments x JOIN academic.students s ON s.id=x.student_id JOIN academic.classrooms c ON c.id=x.classroom_id JOIN academic.academic_years y ON y.id=c.academic_year_id WHERE x.school_id=? AND s.student_code=? AND c.code=? AND y.name=? AND x.status='ACTIVE'",schoolId,student,classroom,year)>0)w.add("Already enrolled in this classroom; commit will be a no-op");
            if(count("SELECT COUNT(*) FROM academic.class_enrollments x JOIN academic.students s ON s.id=x.student_id JOIN academic.classrooms c ON c.id=x.classroom_id JOIN academic.academic_years y ON y.id=c.academic_year_id WHERE x.school_id=? AND s.student_code=? AND y.name=? AND x.status='ACTIVE' AND c.code<>?",schoolId,student,year,classroom)>0)e.add("Student already has an active enrollment in another classroom for this academic year");
        }
    }

    private boolean emailUsedByOtherProfile(BatchContext context,String code,String mail){
        UUID emailUser=context.emailUsers().get(mail.toLowerCase(Locale.ROOT));
        if(emailUser==null)return false;
        UUID own=context.ownUsers().get(code);
        return own==null||!emailUser.equals(own);
    }
    private boolean emailUsedByOtherProfile(UUID schoolId,String table,String codeColumn,String code,String mail){
        List<UUID> own=jdbc.query("SELECT user_id FROM "+table+" WHERE school_id=? AND "+codeColumn+"=? AND user_id IS NOT NULL",(rs,i)->rs.getObject(1,UUID.class),schoolId,code);
        List<UUID> emailUsers=jdbc.query("SELECT id FROM identity.users WHERE lower(email)=lower(?)",(rs,i)->rs.getObject(1,UUID.class),mail);
        return !emailUsers.isEmpty()&&(own.isEmpty()||!emailUsers.getFirst().equals(own.getFirst()));
    }
    private List<List<String>> chunks(Set<String> values){List<String> all=new ArrayList<>(values);List<List<String>> out=new ArrayList<>();for(int i=0;i<all.size();i+=LOOKUP_CHUNK)out.add(all.subList(i,Math.min(i+LOOKUP_CHUNK,all.size())));return out;}
    private String placeholders(int size){return String.join(",",java.util.Collections.nCopies(size,"?"));}
    private String req(Map<String,String>s,String key,List<String>e){String v=opt(s.get(key));if(v==null){e.add(key+" is required");return"";}return v;}
    private String opt(String v){if(v==null)return null;String x=v.trim();return x.isBlank()?null:x;}
    private String email(String v,List<String>e){String x=opt(v);if(x==null)return null;x=x.toLowerCase(Locale.ROOT);if(x.length()>255||!x.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))e.add("email is invalid");return x;}
    private LocalDate date(String v,String field,List<String>e,boolean required){String x=opt(v);if(x==null){if(required)e.add(field+" is required");return null;}try{return LocalDate.parse(x);}catch(Exception ex){e.add(field+" must use YYYY-MM-DD");return null;}}
    private boolean bool(String v,String field,List<String>e){String x=opt(v);if(x==null)return false;x=x.toLowerCase(Locale.ROOT);if(Set.of("true","1","yes","y").contains(x))return true;if(Set.of("false","0","no","n").contains(x))return false;e.add(field+" must be true/false");return false;}
    private int count(String sql,Object...args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}

    public record Validation(Map<String,Object> normalized,List<String> errors,List<String> warnings){}
    public record BatchContext(Set<String> existingCodes,Map<String,UUID> ownUsers,Map<String,UUID> emailUsers){
        static BatchContext empty(){return new BatchContext(Set.of(),Map.of(),Map.of());}
    }
}
