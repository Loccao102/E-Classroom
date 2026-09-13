package com.eclassroom.core.reporting;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReportingService {
    private static final double HIGH_ABSENCE_PERCENT = 20.0;
    private static final double MEDIUM_ABSENCE_PERCENT = 10.0;
    private static final double HIGH_SCORE_BELOW = 5.0;
    private static final double MEDIUM_SCORE_BELOW = 6.5;
    private static final double MEDIUM_LATE_PERCENT = 15.0;
    private static final int MIN_ATTENDANCE_FOR_RISK = 5;
    private static final int MAX_RANGE_DAYS = 400;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AccessService access;

    public ReportingService(JdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.access = access;
    }

    /** Backward-compatible role-aware dashboard alias. */
    public Map<String, Object> school(UUID schoolId, UUID actor) {
        return dashboard(schoolId, actor, null, null, null, null);
    }

    public Map<String, Object> dashboard(UUID schoolId, UUID actor, LocalDate from, LocalDate to,
                                         UUID academicYearId, UUID semesterId) {
        if (access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN")) {
            return schoolDashboard(schoolId, actor, from, to, academicYearId, semesterId);
        }
        access.requireAnyRole(schoolId, actor, "TEACHER");
        return teacherDashboard(schoolId, actor, from, to, academicYearId, semesterId);
    }

    public Map<String, Object> schoolDashboard(UUID schoolId, UUID actor, LocalDate from, LocalDate to,
                                               UUID academicYearId, UUID semesterId) {
        access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN");
        ReportRange range = resolveRange(schoolId, from, to, academicYearId, semesterId);
        MapSqlParameterSource params = params(schoolId, actor, range);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "SCHOOL");
        out.put("range", rangeMap(range));
        out.put("headcount", headcount(schoolId, range));
        out.put("attendance", attendanceSummary(params, range, ""));
        out.put("attendanceTrend", attendanceTrend(params, range, "", null));
        out.put("scores", scoreSummary(params, range, ""));
        out.put("scoreTrend", scoreTrend(params, range, "", null));
        out.put("scoreDistribution", scoreDistribution(params, range, ""));
        out.put("subjectPerformance", subjectPerformance(params, range, ""));
        out.put("classPerformance", classPerformance(params, range));
        out.put("leave", leaveSummary(params));
        out.put("communication", communicationSummary(params));
        out.put("attention", riskRows(schoolId, actor, range, null).stream()
                .filter(row -> !"LOW".equals(row.get("riskLevel"))).limit(12).toList());
        out.put("recentAbsences", recentAbsences(params, range, "", 12));
        return out;
    }

    public Map<String, Object> teacherDashboard(UUID schoolId, UUID actor, LocalDate from, LocalDate to,
                                                UUID academicYearId, UUID semesterId) {
        access.requireAnyRole(schoolId, actor, "TEACHER");
        ReportRange range = resolveRange(schoolId, from, to, academicYearId, semesterId);
        MapSqlParameterSource params = params(schoolId, actor, range);
        String teacherAttendanceScope = " AND t.user_id=:actor";
        String teacherScoreScope = " AND t.user_id=:actor";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "TEACHER");
        out.put("range", rangeMap(range));
        out.put("headcount", teacherHeadcount(params));
        out.put("todayClasses", todayClasses(params));
        List<Map<String, Object>> missing = missingAttendance(params);
        out.put("missingAttendance", missing);
        out.put("workQueue", teacherWorkQueue(params, missing.size()));
        out.put("attendance", attendanceSummary(params, range, teacherAttendanceScope));
        out.put("attendanceTrend", attendanceTrend(params, range, teacherAttendanceScope, null));
        out.put("scores", scoreSummary(params, range, teacherScoreScope));
        out.put("scoreTrend", scoreTrend(params, range, teacherScoreScope, null));
        out.put("subjectPerformance", subjectPerformance(params, range, teacherScoreScope));
        out.put("attention", riskRows(schoolId, actor, range, null).stream()
                .filter(row -> !"LOW".equals(row.get("riskLevel"))).limit(12).toList());
        out.put("recentAbsences", recentAbsences(params, range, teacherAttendanceScope, 10));
        return out;
    }

    public Map<String, Object> student(UUID schoolId, UUID studentId, UUID actor) {
        return student(schoolId, studentId, actor, null, null, null, null);
    }

    public Map<String, Object> student(UUID schoolId, UUID studentId, UUID actor, LocalDate from, LocalDate to,
                                       UUID academicYearId, UUID semesterId) {
        access.requireStudentView(schoolId, actor, studentId);
        ReportRange range = resolveRange(schoolId, from, to, academicYearId, semesterId);
        MapSqlParameterSource params = params(schoolId, actor, range).addValue("studentId", studentId);

        List<Map<String, Object>> profileRows = named.queryForList(
                "SELECT s.id,s.student_code,s.full_name,s.date_of_birth,s.gender,s.status," +
                        "c.id classroom_id,c.name classroom_name,c.code classroom_code " +
                        "FROM academic.students s LEFT JOIN academic.class_enrollments e " +
                        "ON e.student_id=s.id AND e.school_id=s.school_id AND e.status='ACTIVE' " +
                        "LEFT JOIN academic.classrooms c ON c.id=e.classroom_id " +
                        "WHERE s.school_id=:schoolId AND s.id=:studentId ORDER BY e.start_date DESC NULLS LAST LIMIT 1",
                params);
        if (profileRows.isEmpty()) throw ApiException.notFound("Student not found");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", rangeMap(range));
        out.put("student", profileRows.getFirst());
        out.put("attendance", attendanceSummary(params, range, " AND r.student_id=:studentId"));
        out.put("attendanceTrend", attendanceTrend(params, range, " AND r.student_id=:studentId", studentId));
        out.put("subjectAverages", studentSubjectAverages(params, range));
        out.put("recentScores", recentScores(params, range));
        out.put("recentAttendance", recentStudentAttendance(params, range));
        out.put("recentComments", recentComments(params, schoolId, actor));
        out.put("risk", riskForStudent(params, range));
        return out;
    }

    public List<Map<String, Object>> risks(UUID schoolId, UUID actor) {
        return risks(schoolId, actor, null, null, null, null);
    }

    public List<Map<String, Object>> risks(UUID schoolId, UUID actor, LocalDate from, LocalDate to,
                                           UUID academicYearId, UUID semesterId) {
        access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN", "TEACHER");
        ReportRange range = resolveRange(schoolId, from, to, academicYearId, semesterId);
        return riskRows(schoolId, actor, range, null);
    }

    private Map<String, Object> headcount(UUID schoolId, ReportRange range) {
        MapSqlParameterSource p = new MapSqlParameterSource("schoolId", schoolId)
                .addValue("academicYearId", range.academicYearId());
        return named.queryForMap(
                "SELECT " +
                        "(SELECT COUNT(*) FROM academic.students WHERE school_id=:schoolId AND status='ACTIVE') AS \"students\"," +
                        "(SELECT COUNT(*) FROM academic.teachers WHERE school_id=:schoolId AND status='ACTIVE') AS \"teachers\"," +
                        "(SELECT COUNT(*) FROM academic.guardians WHERE school_id=:schoolId AND status='ACTIVE') AS \"guardians\"," +
                        "(SELECT COUNT(*) FROM academic.classrooms WHERE school_id=:schoolId AND status='ACTIVE'" +
                        (range.academicYearId() == null ? "" : " AND academic_year_id=:academicYearId") +
                        ") AS \"classrooms\"",
                p);
    }

    private Map<String, Object> teacherHeadcount(MapSqlParameterSource p) {
        return named.queryForMap(
                "SELECT COUNT(DISTINCT ta.id) AS \"assignments\",COUNT(DISTINCT ta.classroom_id) AS \"classrooms\"," +
                        "COUNT(DISTINCT e.student_id) AS \"students\" " +
                        "FROM academic.teaching_assignments ta JOIN academic.teachers t ON t.id=ta.teacher_id " +
                        "LEFT JOIN academic.class_enrollments e ON e.classroom_id=ta.classroom_id AND e.status='ACTIVE' " +
                        "WHERE ta.school_id=:schoolId AND ta.status='ACTIVE' AND t.user_id=:actor",
                p);
    }

    private Map<String, Object> attendanceSummary(MapSqlParameterSource p, ReportRange range, String extraScope) {
        String sql = "SELECT COUNT(*) AS \"total\"," +
                "COUNT(*) FILTER(WHERE r.status='PRESENT') AS \"present\"," +
                "COUNT(*) FILTER(WHERE r.status='ABSENT') AS \"absent\"," +
                "COUNT(*) FILTER(WHERE r.status='EXCUSED') AS \"excused\"," +
                "COUNT(*) FILTER(WHERE r.status='LATE') AS \"late\"," +
                "COUNT(*) FILTER(WHERE r.status='EARLY_LEAVE') AS \"earlyLeave\"," +
                "ROUND(CASE WHEN COUNT(*)=0 THEN 0 ELSE 100.0*(COUNT(*) FILTER(WHERE r.status IN ('PRESENT','LATE','EARLY_LEAVE')))/COUNT(*) END,1) AS \"attendanceRate\"," +
                "ROUND(CASE WHEN COUNT(*)=0 THEN 0 ELSE 100.0*(COUNT(*) FILTER(WHERE r.status='ABSENT'))/COUNT(*) END,1) AS \"absenceRate\" " +
                "FROM attendance.records r JOIN attendance.sessions se ON se.id=r.attendance_session_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=se.teaching_assignment_id " +
                "JOIN academic.classrooms c ON c.id=se.classroom_id " +
                "JOIN academic.teachers t ON t.id=ta.teacher_id " +
                "WHERE r.school_id=:schoolId AND se.attendance_date BETWEEN :from AND :to" +
                rangeFilter(range) + extraScope;
        return named.queryForMap(sql, p);
    }

    private List<Map<String, Object>> attendanceTrend(MapSqlParameterSource p, ReportRange range,
                                                       String extraScope, UUID ignoredStudent) {
        boolean daily = ChronoUnit.DAYS.between(range.from(), range.to()) <= 45;
        String bucket = daily ? "se.attendance_date" : "date_trunc('week',se.attendance_date)::date";
        String sql = "SELECT " + bucket + " AS \"date\",COUNT(*) AS \"total\"," +
                "COUNT(*) FILTER(WHERE r.status='ABSENT') AS \"absent\"," +
                "COUNT(*) FILTER(WHERE r.status='LATE') AS \"late\"," +
                "ROUND(CASE WHEN COUNT(*)=0 THEN 0 ELSE 100.0*(COUNT(*) FILTER(WHERE r.status IN ('PRESENT','LATE','EARLY_LEAVE')))/COUNT(*) END,1) AS \"attendanceRate\" " +
                "FROM attendance.records r JOIN attendance.sessions se ON se.id=r.attendance_session_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=se.teaching_assignment_id " +
                "JOIN academic.classrooms c ON c.id=se.classroom_id JOIN academic.teachers t ON t.id=ta.teacher_id " +
                "WHERE r.school_id=:schoolId AND se.attendance_date BETWEEN :from AND :to" +
                rangeFilter(range) + extraScope + " GROUP BY 1 ORDER BY 1";
        return named.queryForList(sql, p);
    }

    private Map<String, Object> scoreSummary(MapSqlParameterSource p, ReportRange range, String extraScope) {
        String sql = "WITH scored AS (SELECT ss.student_id,ss.score/NULLIF(a.max_score,0)*10.0 normalized " +
                "FROM grading.student_scores ss JOIN grading.assessments a ON a.id=ss.assessment_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id " +
                "JOIN academic.classrooms c ON c.id=ta.classroom_id JOIN academic.teachers t ON t.id=ta.teacher_id " +
                "WHERE ss.school_id=:schoolId AND ss.status IN ('SUBMITTED','LOCKED') " +
                "AND COALESCE(a.assessment_date,a.created_at::date) BETWEEN :from AND :to" + rangeFilter(range) + extraScope + ") " +
                "SELECT COUNT(*) AS \"publishedScores\",COUNT(DISTINCT student_id) AS \"scoredStudents\"," +
                "ROUND(COALESCE(AVG(normalized),0),2) AS \"averageScore\"," +
                "ROUND(COALESCE(AVG(normalized)*10,0),1) AS \"averagePercent\" FROM scored";
        return named.queryForMap(sql, p);
    }

    private List<Map<String, Object>> scoreTrend(MapSqlParameterSource p, ReportRange range,
                                                  String extraScope, UUID ignoredStudent) {
        boolean daily = ChronoUnit.DAYS.between(range.from(), range.to()) <= 45;
        String date = "COALESCE(a.assessment_date,a.created_at::date)";
        String bucket = daily ? date : "date_trunc('week'," + date + ")::date";
        String sql = "SELECT " + bucket + " AS \"date\",ROUND(AVG(ss.score/NULLIF(a.max_score,0)*10.0),2) AS \"averageScore\"," +
                "COUNT(*) AS \"scores\" FROM grading.student_scores ss JOIN grading.assessments a ON a.id=ss.assessment_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id " +
                "JOIN academic.classrooms c ON c.id=ta.classroom_id JOIN academic.teachers t ON t.id=ta.teacher_id " +
                "WHERE ss.school_id=:schoolId AND ss.status IN ('SUBMITTED','LOCKED') AND " + date + " BETWEEN :from AND :to" +
                rangeFilter(range) + extraScope + " GROUP BY 1 ORDER BY 1";
        return named.queryForList(sql, p);
    }

    private List<Map<String, Object>> scoreDistribution(MapSqlParameterSource p, ReportRange range, String extraScope) {
        String sql = "WITH scored AS (SELECT ss.score/NULLIF(a.max_score,0)*10.0 n FROM grading.student_scores ss " +
                "JOIN grading.assessments a ON a.id=ss.assessment_id JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id " +
                "JOIN academic.classrooms c ON c.id=ta.classroom_id JOIN academic.teachers t ON t.id=ta.teacher_id " +
                "WHERE ss.school_id=:schoolId AND ss.status IN ('SUBMITTED','LOCKED') " +
                "AND COALESCE(a.assessment_date,a.created_at::date) BETWEEN :from AND :to" + rangeFilter(range) + extraScope + ") " +
                "SELECT CASE WHEN n<5 THEN '<5' WHEN n<6.5 THEN '5–6.4' WHEN n<8 THEN '6.5–7.9' " +
                "WHEN n<9 THEN '8–8.9' ELSE '9–10' END AS \"band\",COUNT(*) AS \"count\" FROM scored GROUP BY 1";
        List<Map<String, Object>> rows = named.queryForList(sql, p);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String band : List.of("<5", "5–6.4", "6.5–7.9", "8–8.9", "9–10")) counts.put(band, 0L);
        for (Map<String, Object> row : rows) counts.put(String.valueOf(row.get("band")), ((Number) row.get("count")).longValue());
        List<Map<String, Object>> result = new ArrayList<>();
        counts.forEach((band, count) -> result.add(Map.of("band", band, "count", count)));
        return result;
    }

    private List<Map<String, Object>> subjectPerformance(MapSqlParameterSource p, ReportRange range, String extraScope) {
        String sql = "SELECT sub.id AS \"subjectId\",sub.name AS \"subjectName\"," +
                "ROUND(AVG(ss.score/NULLIF(a.max_score,0)*10.0),2) AS \"averageScore\"," +
                "COUNT(DISTINCT ss.student_id) AS \"students\",COUNT(DISTINCT a.id) AS \"assessments\" " +
                "FROM grading.student_scores ss JOIN grading.assessments a ON a.id=ss.assessment_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id JOIN academic.subjects sub ON sub.id=ta.subject_id " +
                "JOIN academic.classrooms c ON c.id=ta.classroom_id JOIN academic.teachers t ON t.id=ta.teacher_id " +
                "WHERE ss.school_id=:schoolId AND ss.status IN ('SUBMITTED','LOCKED') " +
                "AND COALESCE(a.assessment_date,a.created_at::date) BETWEEN :from AND :to" + rangeFilter(range) + extraScope +
                " GROUP BY sub.id,sub.name ORDER BY \"averageScore\" DESC NULLS LAST,sub.name";
        return named.queryForList(sql, p);
    }

    private List<Map<String, Object>> classPerformance(MapSqlParameterSource p, ReportRange range) {
        String sql = "WITH score AS (SELECT ta.classroom_id,AVG(ss.score/NULLIF(a.max_score,0)*10.0) avg_score " +
                "FROM grading.student_scores ss JOIN grading.assessments a ON a.id=ss.assessment_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id JOIN academic.classrooms c ON c.id=ta.classroom_id " +
                "WHERE ss.school_id=:schoolId AND ss.status IN ('SUBMITTED','LOCKED') " +
                "AND COALESCE(a.assessment_date,a.created_at::date) BETWEEN :from AND :to" + rangeFilter(range) +
                " GROUP BY ta.classroom_id), att AS (SELECT se.classroom_id,COUNT(*) total," +
                "COUNT(*) FILTER(WHERE r.status IN ('PRESENT','LATE','EARLY_LEAVE')) attended " +
                "FROM attendance.records r JOIN attendance.sessions se ON se.id=r.attendance_session_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=se.teaching_assignment_id JOIN academic.classrooms c ON c.id=se.classroom_id " +
                "WHERE r.school_id=:schoolId AND se.attendance_date BETWEEN :from AND :to" + rangeFilter(range) +
                " GROUP BY se.classroom_id) SELECT c.id AS \"classroomId\",c.name AS \"classroomName\"," +
                "ROUND(COALESCE(score.avg_score,0),2) AS \"averageScore\"," +
                "ROUND(CASE WHEN COALESCE(att.total,0)=0 THEN 0 ELSE 100.0*att.attended/att.total END,1) AS \"attendanceRate\" " +
                "FROM academic.classrooms c LEFT JOIN score ON score.classroom_id=c.id LEFT JOIN att ON att.classroom_id=c.id " +
                "WHERE c.school_id=:schoolId AND c.status='ACTIVE'" +
                (range.academicYearId() == null ? "" : " AND c.academic_year_id=:academicYearId") +
                " ORDER BY c.name";
        return named.queryForList(sql, p);
    }

    private Map<String, Object> leaveSummary(MapSqlParameterSource p) {
        return named.queryForMap(
                "SELECT COUNT(*) AS \"total\",COUNT(*) FILTER(WHERE status='SUBMITTED') AS \"submitted\"," +
                        "COUNT(*) FILTER(WHERE status='APPROVED') AS \"approved\",COUNT(*) FILTER(WHERE status='REJECTED') AS \"rejected\" " +
                        "FROM attendance.leave_requests WHERE school_id=:schoolId AND start_date<=:to AND end_date>=:from",
                p);
    }

    private Map<String, Object> communicationSummary(MapSqlParameterSource p) {
        return named.queryForMap(
                "SELECT (SELECT COUNT(*) FROM communication.announcements WHERE school_id=:schoolId AND published_at::date BETWEEN :from AND :to) AS \"announcements\"," +
                        "(SELECT COUNT(*) FROM communication.messages m JOIN communication.conversations c ON c.id=m.conversation_id " +
                        "WHERE c.school_id=:schoolId AND m.created_at::date BETWEEN :from AND :to) AS \"messages\"," +
                        "(SELECT COUNT(*) FROM notification.notifications WHERE school_id=:schoolId AND recipient_user_id=:actor AND read_at IS NULL) AS \"myUnread\"",
                p);
    }

    private List<Map<String, Object>> todayClasses(MapSqlParameterSource p) {
        p.addValue("today", LocalDate.now()).addValue("weekday", LocalDate.now().getDayOfWeek().getValue());
        return named.queryForList(
                "SELECT te.period,te.room,c.id AS \"classroomId\",c.name AS \"classroomName\",sub.name AS \"subjectName\",ta.id AS \"assignmentId\" " +
                        "FROM academic.timetable_entries te JOIN academic.teaching_assignments ta ON ta.id=te.teaching_assignment_id " +
                        "JOIN academic.teachers t ON t.id=ta.teacher_id JOIN academic.classrooms c ON c.id=ta.classroom_id " +
                        "JOIN academic.subjects sub ON sub.id=ta.subject_id WHERE te.school_id=:schoolId AND t.user_id=:actor " +
                        "AND ta.status='ACTIVE' AND te.weekday=:weekday AND (te.valid_from IS NULL OR te.valid_from<=:today) " +
                        "AND (te.valid_to IS NULL OR te.valid_to>=:today) ORDER BY te.period,c.name",
                p);
    }

    private List<Map<String, Object>> missingAttendance(MapSqlParameterSource p) {
        p.addValue("today", LocalDate.now()).addValue("weekday", LocalDate.now().getDayOfWeek().getValue());
        return named.queryForList(
                "SELECT te.period,c.id AS \"classroomId\",c.name AS \"classroomName\",sub.name AS \"subjectName\",ta.id AS \"assignmentId\" " +
                        "FROM academic.timetable_entries te JOIN academic.teaching_assignments ta ON ta.id=te.teaching_assignment_id " +
                        "JOIN academic.teachers t ON t.id=ta.teacher_id JOIN academic.classrooms c ON c.id=ta.classroom_id " +
                        "JOIN academic.subjects sub ON sub.id=ta.subject_id WHERE te.school_id=:schoolId AND t.user_id=:actor AND ta.status='ACTIVE' " +
                        "AND te.weekday=:weekday AND (te.valid_from IS NULL OR te.valid_from<=:today) AND (te.valid_to IS NULL OR te.valid_to>=:today) " +
                        "AND NOT EXISTS (SELECT 1 FROM attendance.sessions se WHERE se.school_id=:schoolId " +
                        "AND se.teaching_assignment_id=ta.id AND se.attendance_date=:today AND se.period=te.period) ORDER BY te.period,c.name",
                p);
    }

    private Map<String, Object> teacherWorkQueue(MapSqlParameterSource p, int missingAttendance) {
        Map<String, Object> counts = named.queryForMap(
                "SELECT (SELECT COUNT(*) FROM attendance.leave_requests lr JOIN academic.class_enrollments e ON e.student_id=lr.student_id AND e.status='ACTIVE' " +
                        "JOIN academic.classrooms c ON c.id=e.classroom_id JOIN academic.teachers ht ON ht.id=c.homeroom_teacher_id " +
                        "WHERE lr.school_id=:schoolId AND lr.status='SUBMITTED' AND ht.user_id=:actor) AS \"pendingLeave\"," +
                        "(SELECT COUNT(*) FROM grading.assessments a JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id " +
                        "JOIN academic.teachers t ON t.id=ta.teacher_id WHERE a.school_id=:schoolId AND a.status='DRAFT' AND t.user_id=:actor) AS \"draftAssessments\"",
                p);
        Map<String, Object> out = new LinkedHashMap<>(counts);
        out.put("missingAttendance", missingAttendance);
        return out;
    }

    private List<Map<String, Object>> recentAbsences(MapSqlParameterSource p, ReportRange range,
                                                      String extraScope, int limit) {
        p.addValue("limit", limit);
        String sql = "SELECT st.id AS \"studentId\",st.full_name AS \"studentName\",c.name AS \"classroomName\"," +
                "se.attendance_date AS \"date\",se.period,sub.name AS \"subjectName\" " +
                "FROM attendance.records r JOIN attendance.sessions se ON se.id=r.attendance_session_id " +
                "JOIN academic.students st ON st.id=r.student_id JOIN academic.teaching_assignments ta ON ta.id=se.teaching_assignment_id " +
                "JOIN academic.classrooms c ON c.id=se.classroom_id JOIN academic.subjects sub ON sub.id=ta.subject_id " +
                "JOIN academic.teachers t ON t.id=ta.teacher_id WHERE r.school_id=:schoolId AND r.status='ABSENT' " +
                "AND se.attendance_date BETWEEN :from AND :to" + rangeFilter(range) + extraScope +
                " ORDER BY se.attendance_date DESC,r.marked_at DESC LIMIT :limit";
        return named.queryForList(sql, p);
    }

    private List<Map<String, Object>> studentSubjectAverages(MapSqlParameterSource p, ReportRange range) {
        String sql = "SELECT sub.id AS \"subjectId\",sub.name AS \"subjectName\"," +
                "ROUND(SUM((ss.score/NULLIF(a.max_score,0)*10.0)*a.weight)/NULLIF(SUM(a.weight),0),2) AS \"weightedAverage\"," +
                "COUNT(*) AS \"publishedScores\" FROM grading.student_scores ss JOIN grading.assessments a ON a.id=ss.assessment_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id JOIN academic.subjects sub ON sub.id=ta.subject_id " +
                "JOIN academic.classrooms c ON c.id=ta.classroom_id WHERE ss.school_id=:schoolId AND ss.student_id=:studentId " +
                "AND ss.status IN ('SUBMITTED','LOCKED') AND COALESCE(a.assessment_date,a.created_at::date) BETWEEN :from AND :to" +
                rangeFilter(range) + " GROUP BY sub.id,sub.name ORDER BY sub.name";
        return named.queryForList(sql, p);
    }

    private List<Map<String, Object>> recentScores(MapSqlParameterSource p, ReportRange range) {
        return named.queryForList(
                "SELECT a.id AS \"assessmentId\",a.title,a.category,sub.name AS \"subjectName\",a.assessment_date AS \"date\"," +
                        "ss.score,a.max_score AS \"maxScore\",ROUND(ss.score/NULLIF(a.max_score,0)*10.0,2) AS \"normalizedScore\" " +
                        "FROM grading.student_scores ss JOIN grading.assessments a ON a.id=ss.assessment_id " +
                        "JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id JOIN academic.subjects sub ON sub.id=ta.subject_id " +
                        "JOIN academic.classrooms c ON c.id=ta.classroom_id WHERE ss.school_id=:schoolId AND ss.student_id=:studentId " +
                        "AND ss.status IN ('SUBMITTED','LOCKED') AND COALESCE(a.assessment_date,a.created_at::date) BETWEEN :from AND :to" +
                        rangeFilter(range) + " ORDER BY COALESCE(a.assessment_date,a.created_at::date) DESC,a.created_at DESC LIMIT 12",
                p);
    }

    private List<Map<String, Object>> recentStudentAttendance(MapSqlParameterSource p, ReportRange range) {
        return named.queryForList(
                "SELECT se.attendance_date AS \"date\",se.period,r.status,r.note,sub.name AS \"subjectName\" " +
                        "FROM attendance.records r JOIN attendance.sessions se ON se.id=r.attendance_session_id " +
                        "JOIN academic.teaching_assignments ta ON ta.id=se.teaching_assignment_id JOIN academic.subjects sub ON sub.id=ta.subject_id " +
                        "JOIN academic.classrooms c ON c.id=se.classroom_id WHERE r.school_id=:schoolId AND r.student_id=:studentId " +
                        "AND se.attendance_date BETWEEN :from AND :to" + rangeFilter(range) +
                        " ORDER BY se.attendance_date DESC,se.period DESC LIMIT 20",
                p);
    }

    private List<Map<String, Object>> recentComments(MapSqlParameterSource p, UUID schoolId, UUID actor) {
        String visibility = "";
        if (!(access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN") || access.hasRole(schoolId, actor, "TEACHER"))) {
            visibility = access.hasRole(schoolId, actor, "PARENT")
                    ? " AND c.visibility IN ('GUARDIAN','STUDENT_AND_GUARDIAN')"
                    : " AND c.visibility='STUDENT_AND_GUARDIAN'";
        }
        return named.queryForList(
                "SELECT c.id,c.body,c.visibility,c.created_at AS \"createdAt\",u.full_name AS \"teacherName\" " +
                        "FROM communication.teacher_comments c JOIN identity.users u ON u.id=c.teacher_user_id " +
                        "WHERE c.school_id=:schoolId AND c.student_id=:studentId" + visibility +
                        " ORDER BY c.created_at DESC,c.id DESC LIMIT 10",
                p);
    }

    private Map<String, Object> riskForStudent(MapSqlParameterSource p, ReportRange range) {
        String sql = "WITH a AS (SELECT COUNT(*) total,COUNT(*) FILTER(WHERE r.status='ABSENT') absent," +
                "COUNT(*) FILTER(WHERE r.status='LATE') late FROM attendance.records r JOIN attendance.sessions se ON se.id=r.attendance_session_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=se.teaching_assignment_id JOIN academic.classrooms c ON c.id=se.classroom_id " +
                "WHERE r.school_id=:schoolId AND r.student_id=:studentId AND se.attendance_date BETWEEN :from AND :to" + rangeFilter(range) + ")," +
                "g AS (SELECT AVG(ss.score/NULLIF(ga.max_score,0)*10.0) avg_score FROM grading.student_scores ss " +
                "JOIN grading.assessments ga ON ga.id=ss.assessment_id JOIN academic.teaching_assignments ta ON ta.id=ga.teaching_assignment_id " +
                "JOIN academic.classrooms c ON c.id=ta.classroom_id WHERE ss.school_id=:schoolId AND ss.student_id=:studentId " +
                "AND ss.status IN ('SUBMITTED','LOCKED') AND COALESCE(ga.assessment_date,ga.created_at::date) BETWEEN :from AND :to" + rangeFilter(range) + ") " +
                "SELECT a.total AS \"attendanceTotal\",a.absent AS \"absentCount\",a.late AS \"lateCount\",ROUND(g.avg_score,2) AS \"averageScore\" FROM a CROSS JOIN g";
        return decorateRisk(named.queryForMap(sql, p), null, null, null);
    }

    private List<Map<String, Object>> riskRows(UUID schoolId, UUID actor, ReportRange range, UUID studentId) {
        boolean admin = access.isPlatformAdmin(actor) || access.hasRole(schoolId, actor, "SCHOOL_ADMIN");
        if (!admin) access.requireAnyRole(schoolId, actor, "TEACHER");
        MapSqlParameterSource p = params(schoolId, actor, range).addValue("studentId", studentId);

        String eligible = admin
                ? "SELECT DISTINCT ON (s.id) s.id student_id,s.full_name,c.name classroom_name FROM academic.students s " +
                  "LEFT JOIN academic.class_enrollments e ON e.student_id=s.id AND e.school_id=s.school_id AND e.status='ACTIVE' " +
                  "LEFT JOIN academic.classrooms c ON c.id=e.classroom_id WHERE s.school_id=:schoolId AND s.status='ACTIVE' " +
                  (studentId == null ? "" : "AND s.id=:studentId ") + "ORDER BY s.id,e.start_date DESC"
                : "SELECT DISTINCT s.id student_id,s.full_name,c.name classroom_name FROM academic.students s " +
                  "JOIN academic.class_enrollments e ON e.student_id=s.id AND e.status='ACTIVE' " +
                  "JOIN academic.classrooms c ON c.id=e.classroom_id JOIN academic.teaching_assignments ta0 ON ta0.classroom_id=c.id AND ta0.status='ACTIVE' " +
                  "JOIN academic.teachers t0 ON t0.id=ta0.teacher_id WHERE s.school_id=:schoolId AND s.status='ACTIVE' AND t0.user_id=:actor " +
                  (studentId == null ? "" : "AND s.id=:studentId ");

        String sql = "WITH eligible AS (" + eligible + "),att AS (SELECT r.student_id,COUNT(*) total," +
                "COUNT(*) FILTER(WHERE r.status='ABSENT') absent,COUNT(*) FILTER(WHERE r.status='LATE') late " +
                "FROM attendance.records r JOIN attendance.sessions se ON se.id=r.attendance_session_id " +
                "JOIN academic.teaching_assignments ta ON ta.id=se.teaching_assignment_id JOIN academic.classrooms c ON c.id=se.classroom_id " +
                "WHERE r.school_id=:schoolId AND se.attendance_date BETWEEN :from AND :to" + rangeFilter(range) +
                " AND r.student_id IN (SELECT student_id FROM eligible) GROUP BY r.student_id), grade AS (" +
                "SELECT ss.student_id,AVG(ss.score/NULLIF(ga.max_score,0)*10.0) avg_score FROM grading.student_scores ss " +
                "JOIN grading.assessments ga ON ga.id=ss.assessment_id JOIN academic.teaching_assignments ta ON ta.id=ga.teaching_assignment_id " +
                "JOIN academic.classrooms c ON c.id=ta.classroom_id WHERE ss.school_id=:schoolId AND ss.status IN ('SUBMITTED','LOCKED') " +
                "AND COALESCE(ga.assessment_date,ga.created_at::date) BETWEEN :from AND :to" + rangeFilter(range) +
                " AND ss.student_id IN (SELECT student_id FROM eligible) GROUP BY ss.student_id) " +
                "SELECT e.student_id AS \"studentId\",e.full_name AS \"fullName\",e.classroom_name AS \"classroomName\"," +
                "COALESCE(att.total,0) AS \"attendanceTotal\",COALESCE(att.absent,0) AS \"absentCount\",COALESCE(att.late,0) AS \"lateCount\"," +
                "ROUND(grade.avg_score,2) AS \"averageScore\" FROM eligible e LEFT JOIN att ON att.student_id=e.student_id " +
                "LEFT JOIN grade ON grade.student_id=e.student_id ORDER BY e.full_name";
        List<Map<String, Object>> rows = named.queryForList(sql, p);
        List<Map<String, Object>> decorated = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            decorated.add(decorateRisk(row, (UUID) row.get("studentId"), String.valueOf(row.get("fullName")),
                    row.get("classroomName") == null ? null : String.valueOf(row.get("classroomName"))));
        }
        decorated.sort((a, b) -> {
            int severity = Integer.compare(riskRank(String.valueOf(a.get("riskLevel"))), riskRank(String.valueOf(b.get("riskLevel"))));
            return severity != 0 ? severity : String.valueOf(a.get("fullName")).compareToIgnoreCase(String.valueOf(b.get("fullName")));
        });
        return decorated;
    }

    private Map<String, Object> decorateRisk(Map<String, Object> row, UUID studentId, String fullName, String classroomName) {
        long total = number(row.get("attendanceTotal")).longValue();
        long absent = number(row.get("absentCount")).longValue();
        long late = number(row.get("lateCount")).longValue();
        Double average = row.get("averageScore") == null ? null : number(row.get("averageScore")).doubleValue();
        double absencePercent = total == 0 ? 0 : 100.0 * absent / total;
        double latePercent = total == 0 ? 0 : 100.0 * late / total;
        List<Map<String, Object>> factors = new ArrayList<>();

        if (total >= MIN_ATTENDANCE_FOR_RISK && absencePercent >= HIGH_ABSENCE_PERCENT) {
            factors.add(factor("ABSENCE", "Tỷ lệ vắng cao", round(absencePercent, 1), HIGH_ABSENCE_PERCENT, "ABOVE", "HIGH"));
        } else if (total >= MIN_ATTENDANCE_FOR_RISK && absencePercent >= MEDIUM_ABSENCE_PERCENT) {
            factors.add(factor("ABSENCE", "Tỷ lệ vắng cần chú ý", round(absencePercent, 1), MEDIUM_ABSENCE_PERCENT, "ABOVE", "MEDIUM"));
        }
        if (average != null && average < HIGH_SCORE_BELOW) {
            factors.add(factor("SCORE", "Điểm trung bình thấp", round(average, 2), HIGH_SCORE_BELOW, "BELOW", "HIGH"));
        } else if (average != null && average < MEDIUM_SCORE_BELOW) {
            factors.add(factor("SCORE", "Điểm trung bình cần theo dõi", round(average, 2), MEDIUM_SCORE_BELOW, "BELOW", "MEDIUM"));
        }
        if (total >= MIN_ATTENDANCE_FOR_RISK && latePercent >= MEDIUM_LATE_PERCENT) {
            factors.add(factor("LATE", "Đi muộn lặp lại", round(latePercent, 1), MEDIUM_LATE_PERCENT, "ABOVE", "MEDIUM"));
        }

        String level = factors.stream().anyMatch(f -> "HIGH".equals(f.get("severity"))) ? "HIGH"
                : factors.isEmpty() ? "LOW" : "MEDIUM";
        Map<String, Object> out = new LinkedHashMap<>();
        if (studentId != null) out.put("studentId", studentId);
        if (fullName != null) out.put("fullName", fullName);
        if (classroomName != null) out.put("classroomName", classroomName);
        out.put("attendanceTotal", total);
        out.put("absentCount", absent);
        out.put("lateCount", late);
        out.put("absencePercent", round(absencePercent, 1));
        out.put("latePercent", round(latePercent, 1));
        out.put("averageScore", average == null ? null : round(average, 2));
        out.put("riskLevel", level);
        out.put("factors", factors);
        return out;
    }

    private Map<String, Object> factor(String code, String label, double value, double threshold,
                                       String direction, String severity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("label", label);
        out.put("value", value);
        out.put("threshold", threshold);
        out.put("direction", direction);
        out.put("severity", severity);
        return out;
    }

    private ReportRange resolveRange(UUID schoolId, LocalDate from, LocalDate to, UUID academicYearId, UUID semesterId) {
        if (semesterId != null) {
            List<PeriodRow> rows = jdbc.query(
                    "SELECT id,name,start_date,end_date,academic_year_id FROM academic.semesters WHERE school_id=? AND id=?",
                    (rs, i) -> new PeriodRow(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                            rs.getObject("academic_year_id", UUID.class)), schoolId, semesterId);
            if (rows.isEmpty()) throw ApiException.badRequest("INVALID_SEMESTER", "Semester does not belong to this school");
            PeriodRow row = rows.getFirst();
            if (academicYearId != null && !academicYearId.equals(row.academicYearId())) {
                throw ApiException.badRequest("INVALID_REPORT_RANGE", "Semester does not belong to the selected academic year");
            }
            return checkedRange(row.start(), row.end(), "Học kỳ " + row.name(), row.academicYearId(), row.id());
        }
        if (academicYearId != null) {
            List<PeriodRow> rows = jdbc.query(
                    "SELECT id,name,start_date,end_date,NULL::uuid academic_year_id FROM academic.academic_years WHERE school_id=? AND id=?",
                    (rs, i) -> new PeriodRow(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class), null),
                    schoolId, academicYearId);
            if (rows.isEmpty()) throw ApiException.badRequest("INVALID_ACADEMIC_YEAR", "Academic year does not belong to this school");
            PeriodRow row = rows.getFirst();
            return checkedRange(row.start(), row.end(), "Năm học " + row.name(), row.id(), null);
        }
        if (from != null || to != null) {
            if (from == null || to == null) throw ApiException.badRequest("INVALID_REPORT_RANGE", "Both from and to dates are required");
            return checkedRange(from, to, from + " → " + to, null, null);
        }
        List<PeriodRow> active = jdbc.query(
                "SELECT id,name,start_date,end_date,NULL::uuid academic_year_id FROM academic.academic_years " +
                        "WHERE school_id=? AND (status='ACTIVE' OR CURRENT_DATE BETWEEN start_date AND end_date) " +
                        "ORDER BY CASE WHEN status='ACTIVE' THEN 0 ELSE 1 END,start_date DESC LIMIT 1",
                (rs, i) -> new PeriodRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class), null), schoolId);
        if (!active.isEmpty()) {
            PeriodRow row = active.getFirst();
            return checkedRange(row.start(), row.end(), "Năm học " + row.name(), row.id(), null);
        }
        LocalDate today = LocalDate.now();
        return checkedRange(today.minusDays(89), today, "90 ngày gần nhất", null, null);
    }

    private ReportRange checkedRange(LocalDate from, LocalDate to, String label, UUID academicYearId, UUID semesterId) {
        if (from.isAfter(to)) throw ApiException.badRequest("INVALID_REPORT_RANGE", "Report start date must not be after end date");
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) throw ApiException.badRequest("REPORT_RANGE_TOO_LARGE", "Report range may not exceed 400 days");
        return new ReportRange(from, to, label, academicYearId, semesterId,
                days <= 45 ? "DAY" : "WEEK");
    }

    private MapSqlParameterSource params(UUID schoolId, UUID actor, ReportRange range) {
        return new MapSqlParameterSource()
                .addValue("schoolId", schoolId).addValue("actor", actor)
                .addValue("from", range.from()).addValue("to", range.to())
                .addValue("academicYearId", range.academicYearId()).addValue("semesterId", range.semesterId());
    }

    private String rangeFilter(ReportRange range) {
        StringBuilder sql = new StringBuilder();
        if (range.academicYearId() != null) sql.append(" AND c.academic_year_id=:academicYearId");
        if (range.semesterId() != null) sql.append(" AND ta.semester_id=:semesterId");
        return sql.toString();
    }

    private Map<String, Object> rangeMap(ReportRange range) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", range.from());
        out.put("to", range.to());
        out.put("label", range.label());
        out.put("academicYearId", range.academicYearId());
        out.put("semesterId", range.semesterId());
        out.put("granularity", range.granularity());
        return out;
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : BigDecimal.ZERO;
    }

    private double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private int riskRank(String level) {
        return switch (level) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    private record ReportRange(LocalDate from, LocalDate to, String label, UUID academicYearId,
                               UUID semesterId, String granularity) {}

    private record PeriodRow(UUID id, String name, LocalDate start, LocalDate end, UUID academicYearId) {}
}
