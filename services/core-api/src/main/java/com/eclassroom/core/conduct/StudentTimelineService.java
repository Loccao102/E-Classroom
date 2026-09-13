package com.eclassroom.core.conduct;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StudentTimelineService {
    private static final Set<String> TYPES = Set.of(
            "ATTENDANCE", "LEAVE", "SCORE", "COMMENT", "CONDUCT", "ANNOUNCEMENT");

    private final JdbcTemplate jdbc;
    private final AccessService access;

    public StudentTimelineService(JdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public TimelinePage page(UUID schoolId, UUID studentId, UUID actor, int limit,
                             OffsetDateTime beforeOccurredAt, UUID beforeId,
                             LocalDate fromDate, LocalDate toDate, List<String> requestedTypes) {
        requireStudent(schoolId, studentId);
        access.requireStudentView(schoolId, actor, studentId);
        int bounded = Math.max(1, Math.min(limit, 100));
        if ((beforeOccurredAt == null) != (beforeId == null)) {
            throw ApiException.badRequest("INVALID_CURSOR", "Both beforeOccurredAt and beforeId are required");
        }
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "toDate must be on or after fromDate");
        }
        Set<String> types = normalizeTypes(requestedTypes);
        Set<String> visibilities = allowedVisibilities(schoolId, actor);
        String timezone = jdbc.queryForObject("SELECT timezone FROM school.schools WHERE id=?", String.class, schoolId);
        ZoneId zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);

        String union = """
                SELECT ar.id entry_id,'ATTENDANCE' entry_type,ar.marked_at occurred_at,
                       'Điểm danh' title,ar.note body,ar.status state,'STUDENT_AND_GUARDIAN' visibility,
                       marker.full_name actor_name,sub.name context_label,NULL::varchar category,NULL::varchar severity,
                       ar.version version,ar.status value_text
                FROM attendance.records ar
                JOIN attendance.sessions ses ON ses.id=ar.attendance_session_id
                LEFT JOIN academic.teaching_assignments ta ON ta.id=ses.teaching_assignment_id
                LEFT JOIN academic.subjects sub ON sub.id=ta.subject_id
                JOIN identity.users marker ON marker.id=ar.marked_by
                WHERE ar.school_id=? AND ar.student_id=?
                UNION ALL
                SELECT lr.id,'LEAVE',COALESCE(lr.reviewed_at,lr.created_at),'Đơn xin nghỉ',lr.reason,lr.status,
                       'STUDENT_AND_GUARDIAN',COALESCE(reviewer.full_name,guardian.full_name),'Nghỉ học',
                       NULL::varchar,NULL::varchar,lr.version,lr.status
                FROM attendance.leave_requests lr
                JOIN identity.users guardian ON guardian.id=lr.guardian_user_id
                LEFT JOIN identity.users reviewer ON reviewer.id=lr.reviewed_by
                WHERE lr.school_id=? AND lr.student_id=?
                UNION ALL
                SELECT ss.id,'SCORE',COALESCE(a.submitted_at,ss.updated_at),a.title,NULL,ss.status,
                       'STUDENT_AND_GUARDIAN',recorder.full_name,sub.name,a.category,NULL::varchar,ss.version,
                       CONCAT(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM ss.score::text)),'/',TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM a.max_score::text)))
                FROM grading.student_scores ss
                JOIN grading.assessments a ON a.id=ss.assessment_id
                JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id
                JOIN academic.subjects sub ON sub.id=ta.subject_id
                JOIN identity.users recorder ON recorder.id=ss.recorded_by
                WHERE ss.school_id=? AND ss.student_id=? AND a.status IN ('SUBMITTED','LOCKED') AND ss.status IN ('SUBMITTED','LOCKED')
                UNION ALL
                SELECT c.id,'COMMENT',c.created_at,'Nhận xét giáo viên',c.body,NULL,c.visibility,
                       teacher.full_name,NULL,NULL::varchar,NULL::varchar,0,c.visibility
                FROM communication.teacher_comments c
                JOIN identity.users teacher ON teacher.id=c.teacher_user_id
                WHERE c.school_id=? AND c.student_id=?
                UNION ALL
                SELECT cr.id,'CONDUCT',cr.occurred_at,cr.title,cr.body,NULL,cr.visibility,
                       recorder.full_name,COALESCE(sub.name,cl.name),cr.category,cr.severity,cr.version,cr.severity
                FROM conduct.records cr
                JOIN identity.users recorder ON recorder.id=cr.recorded_by
                LEFT JOIN academic.classrooms cl ON cl.id=cr.classroom_id
                LEFT JOIN academic.subjects sub ON sub.id=cr.subject_id
                WHERE cr.school_id=? AND cr.student_id=?
                UNION ALL
                SELECT a.id,'ANNOUNCEMENT',a.published_at,a.title,a.body,NULL,'STUDENT_AND_GUARDIAN',
                       publisher.full_name,CASE WHEN a.target_type='SCHOOL' THEN 'Toàn trường' ELSE cl.name END,
                       a.target_type,NULL::varchar,0,CASE WHEN a.pinned THEN 'PINNED' ELSE a.target_type END
                FROM communication.announcements a
                JOIN identity.users publisher ON publisher.id=a.published_by
                JOIN school.schools sch ON sch.id=a.school_id
                LEFT JOIN academic.classrooms cl ON cl.id=a.target_id AND a.target_type='CLASSROOM'
                WHERE a.school_id=? AND (a.target_type='SCHOOL' OR (a.target_type='CLASSROOM' AND EXISTS (
                    SELECT 1 FROM academic.class_enrollments e
                    WHERE e.school_id=? AND e.student_id=? AND e.classroom_id=a.target_id
                      AND e.start_date <= (a.published_at AT TIME ZONE sch.timezone)::date
                      AND (e.end_date IS NULL OR e.end_date >= (a.published_at AT TIME ZONE sch.timezone)::date)
                )))
                """;

        StringBuilder sql = new StringBuilder("SELECT * FROM (").append(union).append(") t WHERE 1=1");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            args.add(schoolId); args.add(studentId);
        }
        args.add(schoolId); args.add(schoolId); args.add(studentId);

        if (!visibilities.isEmpty()) {
            sql.append(" AND visibility IN (")
                    .append(visibilities.stream().map(v -> "?").collect(Collectors.joining(",")))
                    .append(")");
            args.addAll(visibilities);
        }
        if (!types.isEmpty()) {
            sql.append(" AND entry_type IN (")
                    .append(types.stream().map(v -> "?").collect(Collectors.joining(",")))
                    .append(")");
            args.addAll(types);
        }
        if (fromDate != null) {
            sql.append(" AND occurred_at>=?");
            args.add(OffsetDateTime.ofInstant(fromDate.atStartOfDay(zone).toInstant(), ZoneOffset.UTC));
        }
        if (toDate != null) {
            sql.append(" AND occurred_at<?");
            args.add(OffsetDateTime.ofInstant(toDate.plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC));
        }
        if (beforeOccurredAt != null) {
            sql.append(" AND (occurred_at,entry_id)<(?,?)");
            args.add(beforeOccurredAt); args.add(beforeId);
        }
        sql.append(" ORDER BY occurred_at DESC,entry_id DESC LIMIT ?");
        args.add(bounded + 1);

        List<TimelineItem> rows = jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
        boolean hasMore = rows.size() > bounded;
        List<TimelineItem> items = hasMore ? List.copyOf(rows.subList(0, bounded)) : List.copyOf(rows);
        TimelineCursor next = null;
        if (hasMore && !items.isEmpty()) {
            TimelineItem last = items.getLast();
            next = new TimelineCursor(last.occurredAt(), last.id());
        }
        return new TimelinePage(items, next);
    }

    private Set<String> allowedVisibilities(UUID schoolId, UUID actor) {
        if (access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN") || access.hasRole(schoolId, actor, "TEACHER")) {
            return Set.of("STAFF_ONLY", "GUARDIAN", "STUDENT", "STUDENT_AND_GUARDIAN");
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        if (access.hasRole(schoolId, actor, "PARENT")) {
            allowed.add("GUARDIAN"); allowed.add("STUDENT_AND_GUARDIAN");
        }
        if (access.hasRole(schoolId, actor, "STUDENT")) {
            allowed.add("STUDENT"); allowed.add("STUDENT_AND_GUARDIAN");
        }
        return allowed;
    }

    private Set<String> normalizeTypes(List<String> requested) {
        if (requested == null || requested.isEmpty()) return Set.of();
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (String raw : requested) {
            if (raw == null) continue;
            for (String part : raw.split(",")) {
                String value = part.trim().toUpperCase();
                if (value.isEmpty()) continue;
                if (!TYPES.contains(value)) throw ApiException.badRequest("INVALID_TIMELINE_TYPE", "Unsupported timeline type: " + value);
                types.add(value);
            }
        }
        return types;
    }

    private void requireStudent(UUID schoolId, UUID studentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic.students WHERE id=? AND school_id=? AND status='ACTIVE'",
                Integer.class, studentId, schoolId);
        if (count == null || count == 0) throw ApiException.notFound("Student was not found");
    }

    private TimelineItem map(ResultSet rs) throws SQLException {
        Timestamp occurred = rs.getTimestamp("occurred_at");
        return new TimelineItem(
                UUID.fromString(rs.getString("entry_id")), rs.getString("entry_type"),
                occurred.toInstant().atOffset(ZoneOffset.UTC), rs.getString("title"), rs.getString("body"),
                rs.getString("state"), rs.getString("visibility"), rs.getString("actor_name"),
                rs.getString("context_label"), rs.getString("category"), rs.getString("severity"),
                rs.getLong("version"), rs.getString("value_text"));
    }

    public record TimelineItem(UUID id, String type, OffsetDateTime occurredAt, String title, String body,
                               String state, String visibility, String actorName, String contextLabel,
                               String category, String severity, long version, String value) {}
    public record TimelineCursor(OffsetDateTime beforeOccurredAt, UUID beforeId) {}
    public record TimelinePage(List<TimelineItem> items, TimelineCursor nextCursor) {}
}
