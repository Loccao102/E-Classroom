package com.eclassroom.core.communication;

import com.eclassroom.core.identity.AccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommunicationContactService {
    private final JdbcTemplate jdbc;
    private final AccessService access;

    public CommunicationContactService(JdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public List<Map<String, Object>> contacts(UUID schoolId, UUID actor) {
        access.requireMembership(schoolId, actor);
        if (access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN")) {
            return allMembers(schoolId, actor);
        }
        if (access.hasRole(schoolId, actor, "PARENT")) {
            return parentContacts(schoolId, actor);
        }
        if (access.hasRole(schoolId, actor, "TEACHER")) {
            return teacherContacts(schoolId, actor);
        }
        if (access.hasRole(schoolId, actor, "STUDENT")) {
            return studentContacts(schoolId, actor);
        }
        return admins(schoolId, actor);
    }

    private List<Map<String, Object>> allMembers(UUID schoolId, UUID actor) {
        return jdbc.queryForList(
                "SELECT u.id user_id,u.full_name,u.email,string_agg(DISTINCT sm.role,',' ORDER BY sm.role) roles " +
                        "FROM identity.school_memberships sm JOIN identity.users u ON u.id=sm.user_id " +
                        "WHERE sm.school_id=? AND sm.status='ACTIVE' AND u.status='ACTIVE' AND u.id<>? " +
                        "GROUP BY u.id,u.full_name,u.email ORDER BY u.full_name,u.id",
                schoolId, actor);
    }

    private List<Map<String, Object>> parentContacts(UUID schoolId, UUID actor) {
        return jdbc.queryForList(
                "WITH related(user_id) AS (" +
                        "SELECT DISTINCT ht.user_id FROM academic.guardians g " +
                        "JOIN academic.student_guardians sg ON sg.guardian_id=g.id " +
                        "JOIN academic.class_enrollments e ON e.student_id=sg.student_id AND e.status='ACTIVE' " +
                        "JOIN academic.classrooms c ON c.id=e.classroom_id " +
                        "JOIN academic.teachers ht ON ht.id=c.homeroom_teacher_id " +
                        "WHERE g.school_id=? AND g.user_id=? AND ht.user_id IS NOT NULL " +
                        "UNION SELECT DISTINCT at.user_id FROM academic.guardians g " +
                        "JOIN academic.student_guardians sg ON sg.guardian_id=g.id " +
                        "JOIN academic.class_enrollments e ON e.student_id=sg.student_id AND e.status='ACTIVE' " +
                        "JOIN academic.teaching_assignments ta ON ta.classroom_id=e.classroom_id AND ta.status='ACTIVE' " +
                        "JOIN academic.teachers at ON at.id=ta.teacher_id " +
                        "WHERE g.school_id=? AND g.user_id=? AND at.user_id IS NOT NULL " +
                        "UNION SELECT sm.user_id FROM identity.school_memberships sm " +
                        "WHERE sm.school_id=? AND sm.role='SCHOOL_ADMIN' AND sm.status='ACTIVE') " +
                        "SELECT u.id user_id,u.full_name,u.email,string_agg(DISTINCT sm.role,',' ORDER BY sm.role) roles " +
                        "FROM related r JOIN identity.users u ON u.id=r.user_id " +
                        "LEFT JOIN identity.school_memberships sm ON sm.user_id=u.id AND sm.school_id=? AND sm.status='ACTIVE' " +
                        "WHERE u.status='ACTIVE' AND u.id<>? GROUP BY u.id,u.full_name,u.email ORDER BY u.full_name,u.id",
                schoolId, actor, schoolId, actor, schoolId, schoolId, actor);
    }

    private List<Map<String, Object>> studentContacts(UUID schoolId, UUID actor) {
        return jdbc.queryForList(
                "WITH related(user_id) AS (" +
                        "SELECT DISTINCT ht.user_id FROM academic.students s " +
                        "JOIN academic.class_enrollments e ON e.student_id=s.id AND e.status='ACTIVE' " +
                        "JOIN academic.classrooms c ON c.id=e.classroom_id " +
                        "JOIN academic.teachers ht ON ht.id=c.homeroom_teacher_id " +
                        "WHERE s.school_id=? AND s.user_id=? AND ht.user_id IS NOT NULL " +
                        "UNION SELECT DISTINCT at.user_id FROM academic.students s " +
                        "JOIN academic.class_enrollments e ON e.student_id=s.id AND e.status='ACTIVE' " +
                        "JOIN academic.teaching_assignments ta ON ta.classroom_id=e.classroom_id AND ta.status='ACTIVE' " +
                        "JOIN academic.teachers at ON at.id=ta.teacher_id " +
                        "WHERE s.school_id=? AND s.user_id=? AND at.user_id IS NOT NULL " +
                        "UNION SELECT sm.user_id FROM identity.school_memberships sm " +
                        "WHERE sm.school_id=? AND sm.role='SCHOOL_ADMIN' AND sm.status='ACTIVE') " +
                        "SELECT u.id user_id,u.full_name,u.email,string_agg(DISTINCT sm.role,',' ORDER BY sm.role) roles " +
                        "FROM related r JOIN identity.users u ON u.id=r.user_id " +
                        "LEFT JOIN identity.school_memberships sm ON sm.user_id=u.id AND sm.school_id=? AND sm.status='ACTIVE' " +
                        "WHERE u.status='ACTIVE' AND u.id<>? GROUP BY u.id,u.full_name,u.email ORDER BY u.full_name,u.id",
                schoolId, actor, schoolId, actor, schoolId, schoolId, actor);
    }

    private List<Map<String, Object>> teacherContacts(UUID schoolId, UUID actor) {
        return jdbc.queryForList(
                "WITH teacher_classes(classroom_id) AS (" +
                        "SELECT c.id FROM academic.classrooms c JOIN academic.teachers t ON t.id=c.homeroom_teacher_id " +
                        "WHERE c.school_id=? AND c.status='ACTIVE' AND t.user_id=? " +
                        "UNION SELECT ta.classroom_id FROM academic.teaching_assignments ta " +
                        "JOIN academic.teachers t ON t.id=ta.teacher_id " +
                        "WHERE ta.school_id=? AND ta.status='ACTIVE' AND t.user_id=?), " +
                        "related(user_id) AS (" +
                        "SELECT DISTINCT s.user_id FROM teacher_classes tc " +
                        "JOIN academic.class_enrollments e ON e.classroom_id=tc.classroom_id AND e.status='ACTIVE' " +
                        "JOIN academic.students s ON s.id=e.student_id WHERE s.user_id IS NOT NULL " +
                        "UNION SELECT DISTINCT g.user_id FROM teacher_classes tc " +
                        "JOIN academic.class_enrollments e ON e.classroom_id=tc.classroom_id AND e.status='ACTIVE' " +
                        "JOIN academic.student_guardians sg ON sg.student_id=e.student_id " +
                        "JOIN academic.guardians g ON g.id=sg.guardian_id WHERE g.user_id IS NOT NULL AND g.status='ACTIVE' " +
                        "UNION SELECT sm.user_id FROM identity.school_memberships sm " +
                        "WHERE sm.school_id=? AND sm.role='SCHOOL_ADMIN' AND sm.status='ACTIVE') " +
                        "SELECT u.id user_id,u.full_name,u.email,string_agg(DISTINCT sm.role,',' ORDER BY sm.role) roles " +
                        "FROM related r JOIN identity.users u ON u.id=r.user_id " +
                        "LEFT JOIN identity.school_memberships sm ON sm.user_id=u.id AND sm.school_id=? AND sm.status='ACTIVE' " +
                        "WHERE u.status='ACTIVE' AND u.id<>? GROUP BY u.id,u.full_name,u.email ORDER BY u.full_name,u.id",
                schoolId, actor, schoolId, actor, schoolId, schoolId, actor);
    }

    private List<Map<String, Object>> admins(UUID schoolId, UUID actor) {
        return jdbc.queryForList(
                "SELECT u.id user_id,u.full_name,u.email,'SCHOOL_ADMIN' roles FROM identity.school_memberships sm " +
                        "JOIN identity.users u ON u.id=sm.user_id WHERE sm.school_id=? AND sm.role='SCHOOL_ADMIN' " +
                        "AND sm.status='ACTIVE' AND u.status='ACTIVE' AND u.id<>? ORDER BY u.full_name,u.id",
                schoolId, actor);
    }
}
