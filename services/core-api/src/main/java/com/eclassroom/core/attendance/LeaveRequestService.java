package com.eclassroom.core.attendance;

import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LeaveRequestService {
    private static final int MAX_REASON_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final NotificationService notifications;
    private final AuditService audit;

    public LeaveRequestService(JdbcTemplate jdbc, AccessService access, NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public UUID create(UUID schoolId, UUID studentId, UUID guardianUserId, LocalDate start, LocalDate end, String reason) {
        access.requireGuardianOf(schoolId, guardianUserId, studentId);
        validateRequest(start, end, reason);
        String normalizedReason = reason.trim();

        List<String> studentNames = jdbc.query(
                "SELECT full_name FROM academic.students WHERE id=? AND school_id=? AND status='ACTIVE' FOR UPDATE",
                (rs, i) -> rs.getString(1), studentId, schoolId);
        if (studentNames.isEmpty()) {
            throw ApiException.notFound("Student not found");
        }

        Integer overlap = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance.leave_requests " +
                        "WHERE school_id=? AND student_id=? AND status IN ('SUBMITTED','APPROVED') " +
                        "AND start_date<=? AND end_date>=?",
                Integer.class, schoolId, studentId, end, start);
        if (overlap != null && overlap > 0) {
            throw ApiException.conflict("OVERLAPPING_LEAVE_REQUEST", "An active leave request already overlaps this date range");
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO attendance.leave_requests(id,school_id,student_id,guardian_user_id,start_date,end_date,reason) VALUES (?,?,?,?,?,?,?)",
                id, schoolId, studentId, guardianUserId, start, end, normalizedReason);

        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("status", "SUBMITTED");
        submitted.put("studentId", studentId);
        submitted.put("guardianUserId", guardianUserId);
        submitted.put("startDate", start.toString());
        submitted.put("endDate", end.toString());
        submitted.put("version", 0L);
        audit.append(schoolId, guardianUserId, "SUBMIT", "LEAVE_REQUEST", id, null, submitted, "Guardian submitted leave request");

        List<UUID> reviewers = reviewerRecipients(schoolId, studentId);
        notifications.notifyUsers(
                schoolId,
                reviewers,
                "student.leave-request.submitted",
                "New leave request for " + studentNames.getFirst(),
                studentNames.getFirst() + " requested leave from " + start + " to " + end,
                "LEAVE_REQUEST",
                id,
                Map.of(
                        "studentId", studentId,
                        "guardianUserId", guardianUserId,
                        "startDate", start.toString(),
                        "endDate", end.toString(),
                        "status", "SUBMITTED",
                        "version", 0L));
        return id;
    }

    @Transactional
    public void review(UUID id, boolean approve, UUID reviewer) {
        Map<String, Object> request = find(id);
        UUID schoolId = (UUID) request.get("school_id");
        UUID studentId = (UUID) request.get("student_id");
        access.requireHomeroomOrAdmin(schoolId, reviewer, studentId);

        String currentStatus = String.valueOf(request.get("status"));
        if (!"SUBMITTED".equals(currentStatus)) {
            throw ApiException.conflict("LEAVE_ALREADY_REVIEWED", "Leave request already reviewed");
        }

        long currentVersion = ((Number) request.get("version")).longValue();
        LocalDate start = asLocalDate(request.get("start_date"));
        LocalDate end = asLocalDate(request.get("end_date"));
        String status = approve ? "APPROVED" : "REJECTED";

        int updated = jdbc.update(
                "UPDATE attendance.leave_requests SET status=?,reviewed_by=?,reviewed_at=NOW(),updated_at=NOW(),version=version+1 " +
                        "WHERE id=? AND status='SUBMITTED' AND version=?",
                status, reviewer, id, currentVersion);
        if (updated == 0) {
            throw ApiException.conflict("LEAVE_ALREADY_REVIEWED", "Leave request was reviewed by another user");
        }

        int reconciled = 0;
        if (approve) {
            reconciled = jdbc.update(
                    "UPDATE attendance.records ar SET status='EXCUSED',marked_by=?,marked_at=NOW(),version=version+1 " +
                            "FROM attendance.sessions s WHERE ar.attendance_session_id=s.id AND ar.student_id=? " +
                            "AND s.school_id=? AND s.attendance_date BETWEEN ? AND ? AND ar.status='ABSENT'",
                    reviewer, studentId, schoolId, start, end);
        }

        Map<String, Object> oldState = new LinkedHashMap<>();
        oldState.put("status", "SUBMITTED");
        oldState.put("version", currentVersion);
        Map<String, Object> newState = new LinkedHashMap<>();
        newState.put("status", status);
        newState.put("version", currentVersion + 1);
        newState.put("reviewedBy", reviewer);
        newState.put("reconciledAttendanceRecords", reconciled);
        audit.append(
                schoolId,
                reviewer,
                approve ? "APPROVE" : "REJECT",
                "LEAVE_REQUEST",
                id,
                oldState,
                newState,
                approve ? "Leave request approved" : "Leave request rejected");

        String body = "The leave request from " + start + " to " + end + " was " + status.toLowerCase();
        if (approve) {
            body += "; " + reconciled + " absence record(s) reconciled to excused";
        }
        notifications.notifyGuardians(
                schoolId,
                studentId,
                "student.leave-request.reviewed",
                "Leave request " + status.toLowerCase(),
                body,
                "LEAVE_REQUEST",
                id,
                Map.of(
                        "studentId", studentId,
                        "startDate", start.toString(),
                        "endDate", end.toString(),
                        "status", status,
                        "version", currentVersion + 1,
                        "reconciledAttendanceRecords", reconciled));
    }

    public List<Map<String, Object>> forStudent(UUID schoolId, UUID studentId, UUID actor) {
        access.requireStudentView(schoolId, actor, studentId);
        return jdbc.queryForList(
                "SELECT * FROM attendance.leave_requests WHERE school_id=? AND student_id=? ORDER BY created_at DESC",
                schoolId, studentId);
    }

    public List<Map<String, Object>> pending(UUID schoolId, UUID actor) {
        access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN", "TEACHER");
        if (access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN")) {
            return jdbc.queryForList(
                    "SELECT r.*,s.full_name student_name FROM attendance.leave_requests r " +
                            "JOIN academic.students s ON s.id=r.student_id " +
                            "WHERE r.school_id=? AND r.status='SUBMITTED' ORDER BY r.created_at",
                    schoolId);
        }
        return jdbc.queryForList(
                "SELECT DISTINCT r.*,s.full_name student_name FROM attendance.leave_requests r " +
                        "JOIN academic.students s ON s.id=r.student_id " +
                        "JOIN academic.class_enrollments e ON e.student_id=s.id AND e.status='ACTIVE' " +
                        "JOIN academic.classrooms c ON c.id=e.classroom_id " +
                        "JOIN academic.teachers t ON t.id=c.homeroom_teacher_id " +
                        "WHERE r.school_id=? AND r.status='SUBMITTED' AND t.user_id=? ORDER BY r.created_at",
                schoolId, actor);
    }

    private void validateRequest(LocalDate start, LocalDate end, String reason) {
        if (start == null || end == null || end.isBefore(start)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "A valid start and end date are required");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("INVALID_LEAVE_REASON", "Leave reason is required");
        }
        if (reason.trim().length() > MAX_REASON_LENGTH) {
            throw ApiException.badRequest("LEAVE_REASON_TOO_LONG", "Leave reason must be 1000 characters or fewer");
        }
    }

    private List<UUID> reviewerRecipients(UUID schoolId, UUID studentId) {
        return jdbc.query(
                "SELECT DISTINCT user_id FROM (" +
                        "SELECT sm.user_id FROM identity.school_memberships sm " +
                        "WHERE sm.school_id=? AND sm.role='SCHOOL_ADMIN' AND sm.status='ACTIVE' " +
                        "UNION " +
                        "SELECT t.user_id FROM academic.class_enrollments e " +
                        "JOIN academic.classrooms c ON c.id=e.classroom_id " +
                        "JOIN academic.teachers t ON t.id=c.homeroom_teacher_id " +
                        "WHERE e.school_id=? AND e.student_id=? AND e.status='ACTIVE' AND t.status='ACTIVE'" +
                        ") reviewers WHERE user_id IS NOT NULL",
                (rs, i) -> UUID.fromString(rs.getString(1)), schoolId, schoolId, studentId);
    }

    private Map<String, Object> find(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM attendance.leave_requests WHERE id=?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Leave request not found");
        }
        return rows.getFirst();
    }

    private LocalDate asLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
