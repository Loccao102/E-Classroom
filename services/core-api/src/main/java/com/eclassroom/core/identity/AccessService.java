package com.eclassroom.core.identity;

import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class AccessService {
    private final JdbcTemplate jdbc;
    public AccessService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean isPlatformAdmin(UUID userId) { Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM identity.users WHERE id=? AND status='ACTIVE' AND platform_role='SUPER_ADMIN'",Integer.class,userId);return n!=null&&n>0; }
    public boolean hasRole(UUID schoolId,UUID userId,String role){if(isPlatformAdmin(userId))return true;Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM identity.school_memberships WHERE school_id=? AND user_id=? AND role=? AND status='ACTIVE'",Integer.class,schoolId,userId,role);return n!=null&&n>0;}
    public void requireMembership(UUID schoolId, UUID userId) { if(isPlatformAdmin(userId))return; Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM identity.school_memberships WHERE school_id=? AND user_id=? AND status='ACTIVE'",Integer.class,schoolId,userId);if(n==null||n==0)throw ApiException.forbidden("You are not a member of this school"); }
    public void requireAnyRole(UUID schoolId,UUID userId,String...allowed){if(isPlatformAdmin(userId))return;List<String> roles=jdbc.query("SELECT role FROM identity.school_memberships WHERE school_id=? AND user_id=? AND status='ACTIVE'",(rs,i)->rs.getString(1),schoolId,userId);if(roles.stream().noneMatch(r->Arrays.asList(allowed).contains(r)))throw ApiException.forbidden("Insufficient school role");}
    public void requireGuardianOf(UUID schoolId,UUID userId,UUID studentId){if(isPlatformAdmin(userId))return;Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM academic.student_guardians sg JOIN academic.guardians g ON g.id=sg.guardian_id WHERE sg.school_id=? AND sg.student_id=? AND g.user_id=? AND g.status='ACTIVE'",Integer.class,schoolId,studentId,userId);if(n==null||n==0)throw ApiException.forbidden("Not a guardian of this student");}
    public void requireTeacherAssignment(UUID schoolId,UUID userId,UUID assignmentId){if(isPlatformAdmin(userId)||hasRole(schoolId,userId,"SCHOOL_ADMIN"))return;Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM academic.teaching_assignments ta JOIN academic.teachers t ON t.id=ta.teacher_id WHERE ta.school_id=? AND ta.id=? AND ta.status='ACTIVE' AND t.user_id=?",Integer.class,schoolId,assignmentId,userId);if(n==null||n==0)throw ApiException.forbidden("Teaching assignment does not authorize this action");}
    public void requireTeacherOfStudentOrAdmin(UUID schoolId,UUID userId,UUID studentId){if(isPlatformAdmin(userId)||hasRole(schoolId,userId,"SCHOOL_ADMIN"))return;Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM academic.teachers t JOIN academic.teaching_assignments ta ON ta.teacher_id=t.id AND ta.status='ACTIVE' JOIN academic.class_enrollments e ON e.classroom_id=ta.classroom_id AND e.status='ACTIVE' WHERE t.school_id=? AND t.user_id=? AND e.student_id=?",Integer.class,schoolId,userId,studentId);if(n==null||n==0)throw ApiException.forbidden("Teacher is not assigned to this student");}
    public void requireClassTeacherOrAdmin(UUID schoolId,UUID userId,UUID classroomId){if(isPlatformAdmin(userId)||hasRole(schoolId,userId,"SCHOOL_ADMIN"))return;Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM academic.teachers t LEFT JOIN academic.classrooms c ON c.homeroom_teacher_id=t.id LEFT JOIN academic.teaching_assignments ta ON ta.teacher_id=t.id AND ta.classroom_id=? AND ta.status='ACTIVE' WHERE t.school_id=? AND t.user_id=? AND (c.id=? OR ta.id IS NOT NULL)",Integer.class,classroomId,schoolId,userId,classroomId);if(n==null||n==0)throw ApiException.forbidden("Teacher is not assigned to this class");}
    public void requireHomeroomOrAdmin(UUID schoolId,UUID userId,UUID studentId){if(isPlatformAdmin(userId)||hasRole(schoolId,userId,"SCHOOL_ADMIN"))return;Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM academic.class_enrollments e JOIN academic.classrooms c ON c.id=e.classroom_id JOIN academic.teachers t ON t.id=c.homeroom_teacher_id WHERE e.school_id=? AND e.student_id=? AND e.status='ACTIVE' AND t.user_id=?",Integer.class,schoolId,studentId,userId);if(n==null||n==0)throw ApiException.forbidden("Only the homeroom teacher or school admin can review this request");}
    public void requireStudentView(UUID schoolId,UUID userId,UUID studentId){
        if(isPlatformAdmin(userId)||hasRole(schoolId,userId,"SCHOOL_ADMIN"))return;
        Integer self=jdbc.queryForObject("SELECT COUNT(*) FROM academic.students WHERE school_id=? AND id=? AND user_id=?",Integer.class,schoolId,studentId,userId);if(self!=null&&self>0)return;
        Integer guardian=jdbc.queryForObject("SELECT COUNT(*) FROM academic.student_guardians sg JOIN academic.guardians g ON g.id=sg.guardian_id WHERE sg.school_id=? AND sg.student_id=? AND g.user_id=?",Integer.class,schoolId,studentId,userId);if(guardian!=null&&guardian>0)return;
        Integer teacher=jdbc.queryForObject("SELECT COUNT(*) FROM academic.teachers t JOIN academic.teaching_assignments ta ON ta.teacher_id=t.id AND ta.status='ACTIVE' JOIN academic.class_enrollments e ON e.classroom_id=ta.classroom_id AND e.status='ACTIVE' WHERE t.school_id=? AND t.user_id=? AND e.student_id=?",Integer.class,schoolId,userId,studentId);if(teacher!=null&&teacher>0)return;
        throw ApiException.forbidden("You cannot view this student's records");
    }
}
