package com.eclassroom.core.integration;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BulkExportService {
    private static final Set<String> TYPES=Set.of("ROSTER","ATTENDANCE","SCORES","STUDENTS","TEACHERS","GUARDIANS");
    private static final Set<String> SCHOOL_TYPES=Set.of("STUDENTS","TEACHERS","GUARDIANS");
    private final JdbcTemplate jdbc; private final AccessService access; private final TabularExportWriter writer; private final JsonMapper json;
    public BulkExportService(JdbcTemplate jdbc,AccessService access,TabularExportWriter writer,JsonMapper json){this.jdbc=jdbc;this.access=access;this.writer=writer;this.json=json;}

    public ExportFile export(UUID schoolId,UUID actor,String requestedType,String requestedFormat,UUID classroomId,LocalDate from,LocalDate to){
        String type=normalizeType(requestedType),format=normalizeFormat(requestedFormat); boolean schoolScope=SCHOOL_TYPES.contains(type);
        if(schoolScope)access.requireAnyRole(schoolId,actor,"SCHOOL_ADMIN");
        else {if(classroomId==null)throw ApiException.badRequest("EXPORT_SCOPE_REQUIRED","classroomId is required for this export");access.requireClassTeacherOrAdmin(schoolId,actor,classroomId);}
        Data data=switch(type){case"STUDENTS"->students(schoolId);case"TEACHERS"->teachers(schoolId);case"GUARDIANS"->guardians(schoolId);case"ROSTER"->roster(schoolId,classroomId);case"ATTENDANCE"->attendance(schoolId,classroomId,from,to);case"SCORES"->scores(schoolId,classroomId,from,to);default->throw ApiException.badRequest("EXPORT_TYPE_UNSUPPORTED","Unsupported export type");};
        audit(schoolId,actor,type,schoolScope?"SCHOOL":"CLASSROOM",schoolScope?null:classroomId,from,to,format,data.rows().size());
        String extension="XLSX".equals(format)?"xlsx":"csv";String name=type.toLowerCase(Locale.ROOT)+"-"+LocalDate.now()+"."+extension;
        return new ExportFile(name,"XLSX".equals(format)?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv; charset=utf-8",writer.write(format,data.headers(),data.rows()),data.rows().size());
    }

    public ExportFile template(String importType,String format,List<String> headers){String normalized=normalizeFormat(format);String ext="XLSX".equals(normalized)?"xlsx":"csv";return new ExportFile(importType.toLowerCase(Locale.ROOT)+"-template."+ext,"XLSX".equals(normalized)?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv; charset=utf-8",writer.write(normalized,headers,List.of()),0);}

    private Data students(UUID school){List<Map<String,Object>> q=jdbc.queryForList("SELECT student_code,full_name,date_of_birth,gender,email,status FROM academic.students WHERE school_id=? ORDER BY student_code",school);return data(List.of("student_code","full_name","date_of_birth","gender","email","status"),q,"student_code","full_name","date_of_birth","gender","email","status");}
    private Data teachers(UUID school){List<Map<String,Object>> q=jdbc.queryForList("SELECT teacher_code,full_name,email,phone,status FROM academic.teachers WHERE school_id=? ORDER BY teacher_code",school);return data(List.of("teacher_code","full_name","email","phone","status"),q,"teacher_code","full_name","email","phone","status");}
    private Data guardians(UUID school){List<Map<String,Object>> q=jdbc.queryForList("SELECT full_name,email,phone,status FROM academic.guardians WHERE school_id=? ORDER BY full_name",school);return data(List.of("full_name","email","phone","status"),q,"full_name","email","phone","status");}
    private Data roster(UUID school,UUID classroom){List<Map<String,Object>> q=jdbc.queryForList("SELECT s.student_code,s.full_name,s.date_of_birth,s.gender,e.start_date FROM academic.class_enrollments e JOIN academic.students s ON s.id=e.student_id WHERE e.school_id=? AND e.classroom_id=? AND e.status='ACTIVE' ORDER BY s.full_name",school,classroom);return data(List.of("student_code","full_name","date_of_birth","gender","start_date"),q,"student_code","full_name","date_of_birth","gender","start_date");}
    private Data attendance(UUID school,UUID classroom,LocalDate from,LocalDate to){DateRange range=range(from,to);List<Map<String,Object>> q=jdbc.queryForList("SELECT s.student_code,s.full_name,x.attendance_date,x.period,r.status,r.note FROM attendance.records r JOIN attendance.sessions x ON x.id=r.attendance_session_id JOIN academic.students s ON s.id=r.student_id WHERE r.school_id=? AND x.classroom_id=? AND x.attendance_date BETWEEN ? AND ? ORDER BY x.attendance_date,x.period,s.full_name",school,classroom,range.from(),range.to());return data(List.of("student_code","full_name","date","period","status","note"),q,"student_code","full_name","attendance_date","period","status","note");}
    private Data scores(UUID school,UUID classroom,LocalDate from,LocalDate to){DateRange range=range(from,to);List<Map<String,Object>> q=jdbc.queryForList("SELECT s.student_code,s.full_name,sub.name subject,a.title assessment,a.assessment_date,ss.score,a.max_score,ss.status FROM grading.student_scores ss JOIN grading.assessments a ON a.id=ss.assessment_id JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id JOIN academic.subjects sub ON sub.id=ta.subject_id JOIN academic.students s ON s.id=ss.student_id WHERE ss.school_id=? AND ta.classroom_id=? AND a.status IN ('SUBMITTED','LOCKED') AND a.assessment_date BETWEEN ? AND ? ORDER BY a.assessment_date,sub.name,s.full_name",school,classroom,range.from(),range.to());return data(List.of("student_code","full_name","subject","assessment","assessment_date","score","max_score","status"),q,"student_code","full_name","subject","assessment","assessment_date","score","max_score","status");}
    private Data data(List<String> headers,List<Map<String,Object>> source,String...keys){List<List<?>> rows=new ArrayList<>();for(Map<String,Object> row:source){List<Object> values=new ArrayList<>();for(String key:keys)values.add(row.get(key));rows.add(values);}return new Data(headers,rows);}
    private DateRange range(LocalDate from,LocalDate to){LocalDate end=to==null?LocalDate.now():to,start=from==null?end.minusDays(30):from;if(end.isBefore(start))throw ApiException.badRequest("INVALID_EXPORT_RANGE","to must be on or after from");if(start.plusDays(400).isBefore(end))throw ApiException.badRequest("EXPORT_RANGE_TOO_LARGE","Export range may not exceed 400 days");return new DateRange(start,end);}
    private void audit(UUID school,UUID actor,String type,String scopeType,UUID scope,LocalDate from,LocalDate to,String format,int count){try{String params=json.writeValueAsString(Map.of("from",from==null?"":from.toString(),"to",to==null?"":to.toString()));jdbc.update("INSERT INTO integration.export_audit(id,school_id,export_type,scope_type,scope_id,parameters,format,row_count,exported_by) VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?,?)",UUID.randomUUID(),school,type,scopeType,scope,params,format,count,actor);}catch(Exception ex){throw new IllegalStateException("Cannot audit export",ex);}}
    private String normalizeType(String value){String v=value==null?"":value.trim().toUpperCase(Locale.ROOT);if(!TYPES.contains(v))throw ApiException.badRequest("EXPORT_TYPE_UNSUPPORTED","Unsupported export type");return v;}
    private String normalizeFormat(String value){String v=value==null?"CSV":value.trim().toUpperCase(Locale.ROOT);if(!Set.of("CSV","XLSX").contains(v))throw ApiException.badRequest("EXPORT_FORMAT_UNSUPPORTED","Format must be CSV or XLSX");return v;}
    public record ExportFile(String fileName,String contentType,byte[] bytes,int rowCount){}
    private record Data(List<String> headers,List<List<?>> rows){}
    private record DateRange(LocalDate from,LocalDate to){}
}
