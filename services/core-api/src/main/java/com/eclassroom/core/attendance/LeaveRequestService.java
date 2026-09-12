package com.eclassroom.core.attendance;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LeaveRequestService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final NotificationService notifications;

    public LeaveRequestService(JdbcTemplate jdbc, AccessService access, NotificationService notifications) {
        this.jdbc = jdbc;
        this.access = access;
        this.notifications = notifications;
    }

    @Transactional
    public UUID create(UUID schoolId, UUID studentId, UUID guardianUserId, LocalDate start, LocalDate end, String reason) {
        access.requireGuardianOf(schoolId, guardianUserId, studentId);
        if (end.isBefore(start)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "End date cannot be before start date");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO attendance.leave_requests(id,school_id,student_id,guardian_user_id,start_date,end_date,reason) VALUES (?,?,?,?,?,?,?)",
                id, schoolId, studentId, guardianUserId, start, end, reason);
        return id;
    }

    @Transactional
    public void review(UUID id, boolean approve, UUID reviewer) {
        Map<String, Object> request = find(id);
        UUID schoolId = (UUID) request.get("school_id");
        UUID studentId = (UUID) request.get("student_id");
        access.requireHomeroomOrAdmin(schoolId, reviewer, studentId);
        if (!"SUBMITTED".equals(request.get("status"))) {
            throw ApiException.conflict("LEAVE_ALREADY_REVIEWED", "Leave request already reviewed");
        }
        String status = approve ? "APPROVED" : "REJECTED";
        jdbc.update("UPDATE attendance.leave_requests SET status=?,reviewed_by=?,reviewed_at=NOW() WHERE id=?", status, reviewer, id);
        if (approve) {
            jdbc.update("UPDATE attendance.records ar SET status='EXCUSED',marked_by=?,marked_at=NOW(),version=version+1 FROM attendance.sessions s WHERE ar.attendance_session_id=s.id AND ar.student_id=? AND s.attendance_date BETWEEN ? AND ? AND ar.status='ABSENT'",
                    reviewer, studentId, request.get("start_date"), request.get("end_date"));
        }
        notifications.notifyGuardians(
                schoolId,
                studentId,
                "leave-request.reviewed",
                "Leave request " + status.toLowerCase(),
                "The leave request from " + request.get("start_date") + " to " + request.get("end_date") + " was " + status.toLowerCase(),
                "LEAVE_REQUEST",
                id);
    }

    public List<Map<String, Object>> forStudent(UUID schoolId, UUID studentId, UUID actor) {
        access.requireStudentView(schoolId, actor, studentId);
        return jdbc.queryForList("SELECT * FROM attendance.leave_requests WHERE school_id=? AND student_id=? ORDER BY created_at DESC",
                schoolId, studentId);
    }

    public List<Map<String, Object>> pending(UUID schoolId, UUID actor) {
        access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN", "TEACHER");
        if (access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN")) {
            return jdbc.queryForList(
                    "SELECT r.*,s.full_name student_name FROM attendance.leave_requests r JOIN academic.students s ON s.id=r.student_id WHERE r.school_id=? AND r.status='SUBMITTED' ORDER BY r.created_at",
                    schoolId);
        }
        return jdbc.queryForList(
                "SELECT r.*,s.full_name student_name FROM attendance.leave_requests r JOIN academic.students s ON s.id=r.student_id JOIN academic.class_enrollments e ON e.student_id=s.id AND e.status='ACTIVE' JOIN academic.classrooms c ON c.id=e.classroom_id JOIN academic.teachers t ON t.id=c.homeroom_teacher_id WHERE r.school_id=? AND r.status='SUBMITTED' AND t.user_id=? ORDER BY r.created_at",
                schoolId,
                actor);
    }

    private Map<String, Object> find(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM attendance.leave_requests WHERE id=?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Leave request not found");
        }
        return rows.getFirst();
    }
}
