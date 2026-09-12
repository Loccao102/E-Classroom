package com.eclassroom.core.academic;

import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AcademicService {
    private final JdbcTemplate jdbc; private final PasswordEncoder passwords;
    public AcademicService(JdbcTemplate jdbc,PasswordEncoder passwords){this.jdbc=jdbc;this.passwords=passwords;}

    @Transactional public UUID academicYear(UUID schoolId,String name,LocalDate start,LocalDate end){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",id,schoolId,name,start,end);return id;}
    @Transactional public UUID semester(UUID schoolId,UUID yearId,String name,LocalDate start,LocalDate end){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.semesters(id,school_id,academic_year_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,?,'ACTIVE')",id,schoolId,yearId,name,start,end);return id;}
    @Transactional public UUID gradeLevel(UUID schoolId,String name,int sortOrder){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.grade_levels(id,school_id,name,sort_order) VALUES (?,?,?,?)",id,schoolId,name,sortOrder);return id;}
    @Transactional public UUID subject(UUID schoolId,String code,String name){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)",id,schoolId,code.toUpperCase(),name);return id;}

    @Transactional public UUID teacher(UUID schoolId,String code,String fullName,String email,String phone,String password){UUID userId=createAccount(schoolId,email,password,fullName,"TEACHER");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,email,phone) VALUES (?,?,?,?,?,?,?)",id,schoolId,userId,code.toUpperCase(),fullName,email,phone);return id;}
    @Transactional public UUID student(UUID schoolId,String code,String fullName,LocalDate dob,String gender,String email,String password){UUID userId=createAccount(schoolId,email,password,fullName,"STUDENT");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.students(id,school_id,user_id,student_code,full_name,date_of_birth,gender) VALUES (?,?,?,?,?,?,?)",id,schoolId,userId,code.toUpperCase(),fullName,dob,gender);return id;}
    @Transactional public UUID guardian(UUID schoolId,String fullName,String email,String phone,String password){UUID userId=createAccount(schoolId,email,password,fullName,"PARENT");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.guardians(id,school_id,user_id,full_name,email,phone) VALUES (?,?,?,?,?,?)",id,schoolId,userId,fullName,email,phone);return id;}
    @Transactional public void linkGuardian(UUID schoolId,UUID studentId,UUID guardianId,String relationship,boolean primary){jdbc.update("INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact) VALUES (?,?,?,?,?) ON CONFLICT(student_id,guardian_id) DO UPDATE SET relationship=EXCLUDED.relationship,primary_contact=EXCLUDED.primary_contact",schoolId,studentId,guardianId,relationship,primary);}

    @Transactional public UUID classroom(UUID schoolId,UUID yearId,UUID gradeLevelId,String code,String name,UUID homeroomTeacherId){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.classrooms(id,school_id,academic_year_id,grade_level_id,code,name,homeroom_teacher_id) VALUES (?,?,?,?,?,?,?)",id,schoolId,yearId,gradeLevelId,code.toUpperCase(),name,homeroomTeacherId);return id;}
    @Transactional public UUID enroll(UUID schoolId,UUID classroomId,UUID studentId,LocalDate start){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')",id,schoolId,classroomId,studentId,start);return id;}
    @Transactional public UUID teachingAssignment(UUID schoolId,UUID teacherId,UUID classroomId,UUID subjectId,UUID semesterId){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,semester_id,status) VALUES (?,?,?,?,?,?,'ACTIVE')",id,schoolId,teacherId,classroomId,subjectId,semesterId);return id;}
    @Transactional public UUID timetable(UUID schoolId,UUID assignmentId,int weekday,int period,String room,LocalDate from,LocalDate to){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO academic.timetable_entries(id,school_id,teaching_assignment_id,weekday,period,room,valid_from,valid_to) VALUES (?,?,?,?,?,?,?,?)",id,schoolId,assignmentId,weekday,period,room,from,to);return id;}

    public List<Map<String,Object>> list(UUID schoolId,String type){return switch(type){
        case "academic-years"->jdbc.queryForList("SELECT * FROM academic.academic_years WHERE school_id=? ORDER BY start_date DESC",schoolId);
        case "semesters"->jdbc.queryForList("SELECT * FROM academic.semesters WHERE school_id=? ORDER BY start_date DESC",schoolId);
        case "subjects"->jdbc.queryForList("SELECT * FROM academic.subjects WHERE school_id=? ORDER BY name",schoolId);
        case "teachers"->jdbc.queryForList("SELECT id,teacher_code,full_name,email,phone,status,user_id FROM academic.teachers WHERE school_id=? ORDER BY full_name",schoolId);
        case "students"->jdbc.queryForList("SELECT id,student_code,full_name,date_of_birth,gender,status,user_id FROM academic.students WHERE school_id=? ORDER BY full_name",schoolId);
        case "guardians"->jdbc.queryForList("SELECT id,full_name,email,phone,status,user_id FROM academic.guardians WHERE school_id=? ORDER BY full_name",schoolId);
        case "classrooms"->jdbc.queryForList("SELECT c.*,g.name grade_name,y.name academic_year_name,t.full_name homeroom_teacher_name FROM academic.classrooms c LEFT JOIN academic.grade_levels g ON g.id=c.grade_level_id JOIN academic.academic_years y ON y.id=c.academic_year_id LEFT JOIN academic.teachers t ON t.id=c.homeroom_teacher_id WHERE c.school_id=? ORDER BY c.code",schoolId);
        case "teaching-assignments"->jdbc.queryForList("SELECT ta.id,ta.teacher_id,ta.classroom_id,ta.subject_id,ta.semester_id,ta.status,t.full_name teacher_name,c.name classroom_name,s.name subject_name FROM academic.teaching_assignments ta JOIN academic.teachers t ON t.id=ta.teacher_id JOIN academic.classrooms c ON c.id=ta.classroom_id JOIN academic.subjects s ON s.id=ta.subject_id WHERE ta.school_id=? ORDER BY c.name,s.name",schoolId);
        case "timetable"->jdbc.queryForList("SELECT e.*,t.full_name teacher_name,c.name classroom_name,s.name subject_name FROM academic.timetable_entries e JOIN academic.teaching_assignments a ON a.id=e.teaching_assignment_id JOIN academic.teachers t ON t.id=a.teacher_id JOIN academic.classrooms c ON c.id=a.classroom_id JOIN academic.subjects s ON s.id=a.subject_id WHERE e.school_id=? ORDER BY weekday,period",schoolId);
        default->throw ApiException.badRequest("UNKNOWN_RESOURCE","Unknown academic resource");};}

    public List<Map<String,Object>> myChildren(UUID schoolId,UUID userId){return jdbc.queryForList("SELECT s.id,s.student_code,s.full_name,s.date_of_birth,s.gender,c.id classroom_id,c.name classroom_name FROM academic.guardians g JOIN academic.student_guardians sg ON sg.guardian_id=g.id JOIN academic.students s ON s.id=sg.student_id LEFT JOIN academic.class_enrollments e ON e.student_id=s.id AND e.status='ACTIVE' LEFT JOIN academic.classrooms c ON c.id=e.classroom_id WHERE g.school_id=? AND g.user_id=? ORDER BY s.full_name",schoolId,userId);}
    public List<Map<String,Object>> myClasses(UUID schoolId,UUID userId){return jdbc.queryForList("SELECT ta.id teaching_assignment_id,c.id classroom_id,c.name classroom_name,s.id subject_id,s.name subject_name,sem.name semester_name FROM academic.teachers t JOIN academic.teaching_assignments ta ON ta.teacher_id=t.id AND ta.status='ACTIVE' JOIN academic.classrooms c ON c.id=ta.classroom_id JOIN academic.subjects s ON s.id=ta.subject_id LEFT JOIN academic.semesters sem ON sem.id=ta.semester_id WHERE t.school_id=? AND t.user_id=? ORDER BY c.name,s.name",schoolId,userId);}
    public List<Map<String,Object>> roster(UUID schoolId,UUID classroomId){return jdbc.queryForList("SELECT s.id,s.student_code,s.full_name FROM academic.class_enrollments e JOIN academic.students s ON s.id=e.student_id WHERE e.school_id=? AND e.classroom_id=? AND e.status='ACTIVE' ORDER BY s.full_name",schoolId,classroomId);}

    private UUID createAccount(UUID schoolId,String email,String password,String fullName,String role){
        if(email==null||email.isBlank()) return null; if(password==null||password.isBlank()) throw ApiException.badRequest("PASSWORD_REQUIRED","Password is required when creating an account");
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM identity.users WHERE lower(email)=lower(?)",Integer.class,email); if(exists!=null&&exists>0) throw ApiException.conflict("EMAIL_EXISTS","Email already exists");
        UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO identity.users(id,email,password_hash,full_name) VALUES (?,?,?,?)",id,email.toLowerCase(),passwords.encode(password),fullName);
        jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role) VALUES (?,?,?)",id,schoolId,role); return id;
    }
}
