package com.eclassroom.core.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
class RelationshipImportValidator {
    private final JdbcTemplate jdbc;
    RelationshipImportValidator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    ImportValidationService.ValidationResult validate(UUID schoolId,String type,Map<String,String> raw,Set<String> seen){
        Map<String,String> n=new LinkedHashMap<>(); List<String> e=new ArrayList<>(),w=new ArrayList<>();
        if("GUARDIAN_LINK".equals(type)) guardianLink(schoolId,raw,n,e,w,seen); else enrollment(schoolId,raw,n,e,w,seen);
        return new ImportValidationService.ValidationResult(n,e,w);
    }

    private void guardianLink(UUID school,Map<String,String> r,Map<String,String> n,List<String> e,List<String> w,Set<String> seen){
        String student=upper(required(r,"student_code",e)),email=email(r.get("guardian_email"),e),phone=clean(r.get("guardian_phone")),relationship=upper(required(r,"relationship",e));
        boolean primary=bool(r.get("primary_contact"),"primary_contact",e);
        if(email.isBlank()&&phone.isBlank())e.add("GUARDIAN_EMAIL_OR_PHONE_REQUIRED");
        if(!relationship.isBlank()&&!Set.of("PARENT","GUARDIAN","OTHER").contains(relationship))e.add("RELATIONSHIP_INVALID");
        n.put("student_code",student);n.put("guardian_email",email);n.put("guardian_phone",phone);n.put("relationship",relationship);n.put("primary_contact",String.valueOf(primary));
        duplicate("link:"+student+":"+email+":"+phone,seen,e);
        if(count("SELECT COUNT(*) FROM academic.students WHERE school_id=? AND upper(student_code)=?",school,student)==0)e.add("STUDENT_NOT_FOUND_OR_CROSS_TENANT");
        List<Map<String,Object>> guardians=jdbc.queryForList("SELECT id FROM academic.guardians WHERE school_id=? AND ((?<>'' AND lower(email)=lower(?)) OR (?<>'' AND phone=?))",school,email,email,phone,phone);
        if(guardians.isEmpty())e.add("GUARDIAN_NOT_FOUND_OR_CROSS_TENANT"); else if(guardians.size()>1)e.add("GUARDIAN_REFERENCE_AMBIGUOUS");
        if(e.isEmpty()&&count("SELECT COUNT(*) FROM academic.student_guardians sg JOIN academic.students s ON s.id=sg.student_id JOIN academic.guardians g ON g.id=sg.guardian_id WHERE sg.school_id=? AND upper(s.student_code)=? AND ((?<>'' AND lower(g.email)=lower(?)) OR (?<>'' AND g.phone=?))",school,student,email,email,phone,phone)>0)w.add("LINK_ALREADY_EXISTS_WILL_UPDATE");
    }

    private void enrollment(UUID school,Map<String,String> r,Map<String,String> n,List<String> e,List<String> w,Set<String> seen){
        String student=upper(required(r,"student_code",e)),year=required(r,"academic_year_name",e),classCode=upper(required(r,"classroom_code",e)),start=date(r.get("start_date"),e);
        n.put("student_code",student);n.put("academic_year_name",year);n.put("classroom_code",classCode);n.put("start_date",start);
        duplicate("enrollment:"+year.toLowerCase(Locale.ROOT)+":"+classCode+":"+student,seen,e);
        List<Map<String,Object>> students=jdbc.queryForList("SELECT id FROM academic.students WHERE school_id=? AND upper(student_code)=?",school,student);
        if(students.isEmpty()){e.add("STUDENT_NOT_FOUND_OR_CROSS_TENANT");return;}
        List<Map<String,Object>> classes=jdbc.queryForList("SELECT c.id FROM academic.classrooms c JOIN academic.academic_years y ON y.id=c.academic_year_id WHERE c.school_id=? AND upper(c.code)=? AND lower(y.name)=lower(?)",school,classCode,year);
        if(classes.isEmpty()){e.add("CLASSROOM_NOT_FOUND_OR_CROSS_TENANT");return;} if(classes.size()>1){e.add("CLASSROOM_REFERENCE_AMBIGUOUS");return;}
        UUID studentId=(UUID)students.getFirst().get("id"),classId=(UUID)classes.getFirst().get("id");
        List<Map<String,Object>> active=jdbc.queryForList("SELECT classroom_id FROM academic.class_enrollments WHERE school_id=? AND student_id=? AND status='ACTIVE'",school,studentId);
        if(!active.isEmpty()){if(classId.equals(active.getFirst().get("classroom_id")))w.add("STUDENT_ALREADY_ENROLLED");else e.add("STUDENT_ALREADY_ACTIVE_IN_ANOTHER_CLASS");}
    }

    private int count(String sql,Object...args){Integer value=jdbc.queryForObject(sql,Integer.class,args);return value==null?0:value;}
    private String required(Map<String,String> raw,String key,List<String> errors){String value=clean(raw.get(key));if(value.isBlank())errors.add(key.toUpperCase(Locale.ROOT)+"_REQUIRED");return value;}
    private String email(String value,List<String> errors){String v=clean(value).toLowerCase(Locale.ROOT);if(!v.isBlank()&&(!v.contains("@")||v.startsWith("@")||v.endsWith("@")))errors.add("EMAIL_INVALID");return v;}
    private String date(String value,List<String> errors){String v=clean(value);if(v.isBlank()){errors.add("START_DATE_REQUIRED");return "";}try{return LocalDate.parse(v).toString();}catch(Exception ex){errors.add("START_DATE_INVALID");return v;}}
    private boolean bool(String value,String field,List<String> errors){String v=clean(value).toLowerCase(Locale.ROOT);if(v.isBlank()||Set.of("false","0","no","n").contains(v))return false;if(Set.of("true","1","yes","y","x").contains(v))return true;errors.add(field.toUpperCase(Locale.ROOT)+"_INVALID");return false;}
    private void duplicate(String key,Set<String> seen,List<String> errors){if(!seen.add(key))errors.add("DUPLICATE_ROW_KEY");}
    private String clean(String value){return value==null?"":value.trim();} private String upper(String value){return clean(value).toUpperCase(Locale.ROOT);}
}
