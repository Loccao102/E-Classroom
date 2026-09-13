package com.eclassroom.core.grading;

import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.integration.OutboxService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.CorrelationIdFilter;
import com.eclassroom.core.shared.api.ApiException;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GradingService {
    private static final int MAX_SCORE_BATCH = 1000;
    private static final int MAX_REVISION_PAGE = 100;

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final NotificationService notifications;
    private final AuditService audit;
    private final OutboxService outbox;

    public GradingService(
            JdbcTemplate jdbc,
            AccessService access,
            NotificationService notifications,
            AuditService audit,
            OutboxService outbox) {
        this.jdbc = jdbc;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Transactional
    public UUID create(
            UUID schoolId,
            UUID assignmentId,
            UUID semesterId,
            String title,
            String category,
            BigDecimal max,
            BigDecimal weight,
            LocalDate date,
            UUID actor) {
        access.requireTeacherAssignment(schoolId, actor, assignmentId);
        validateAssessment(title, category, max, weight);

        Map<String, Object> assignment = jdbc.queryForMap(
                "SELECT school_id,classroom_id,semester_id FROM academic.teaching_assignments " +
                        "WHERE id=? AND school_id=? AND status='ACTIVE'",
                assignmentId, schoolId);
        UUID assignmentSemester = (UUID) assignment.get("semester_id");
        if (semesterId != null) {
            List<Map<String, Object>> semesters = jdbc.queryForList(
                    "SELECT id,start_date,end_date FROM academic.semesters WHERE id=? AND school_id=?",
                    semesterId, schoolId);
            if (semesters.isEmpty()) {
                throw ApiException.badRequest("INVALID_SEMESTER", "Semester does not belong to this school");
            }
            if (assignmentSemester != null && !assignmentSemester.equals(semesterId)) {
                throw ApiException.badRequest("SEMESTER_MISMATCH", "Assessment semester does not match the teaching assignment");
            }
            if (date != null) {
                LocalDate start = asLocalDate(semesters.getFirst().get("start_date"));
                LocalDate end = asLocalDate(semesters.getFirst().get("end_date"));
                if (date.isBefore(start) || date.isAfter(end)) {
                    throw ApiException.badRequest("ASSESSMENT_DATE_OUTSIDE_SEMESTER", "Assessment date is outside the semester");
                }
            }
        }

        UUID id = UUID.randomUUID();
        String normalizedTitle = title.trim();
        String normalizedCategory = category.trim().toUpperCase(Locale.ROOT);
        jdbc.update(
                "INSERT INTO grading.assessments(id,school_id,teaching_assignment_id,semester_id,title,category,max_score,weight,assessment_date,created_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, schoolId, assignmentId, semesterId, normalizedTitle, normalizedCategory, max, weight, date, actor);
        audit.append(
                schoolId,
                actor,
                "CREATE",
                "ASSESSMENT",
                id,
                null,
                Map.of(
                        "status", "DRAFT",
                        "title", normalizedTitle,
                        "category", normalizedCategory,
                        "maxScore", max,
                        "weight", weight,
                        "version", 0L),
                "Assessment created");
        return id;
    }

    @Transactional
    public void saveScores(UUID assessmentId, List<ScoreInput> scores, String reason, UUID actor) {
        Map<String, Object> assessment = assessment(assessmentId);
        UUID schoolId = (UUID) assessment.get("school_id");
        UUID assignmentId = (UUID) assessment.get("teaching_assignment_id");
        access.requireTeacherAssignment(schoolId, actor, assignmentId);
        requireDraft(assessment);
        validateScoreBatch(scores, (BigDecimal) assessment.get("max_score"));

        UUID classroomId = jdbc.queryForObject(
                "SELECT classroom_id FROM academic.teaching_assignments WHERE id=? AND school_id=? AND status='ACTIVE'",
                UUID.class, assignmentId, schoolId);
        List<UUID> studentIds = scores.stream().map(ScoreInput::studentId).toList();
        String inClause = placeholders(studentIds.size());

        List<Object> enrollmentArgs = new ArrayList<>();
        enrollmentArgs.add(schoolId);
        enrollmentArgs.add(classroomId);
        enrollmentArgs.addAll(studentIds);
        Set<UUID> enrolled = new LinkedHashSet<>(jdbc.query(
                "SELECT DISTINCT student_id FROM academic.class_enrollments " +
                        "WHERE school_id=? AND classroom_id=? AND status='ACTIVE' AND student_id IN (" + inClause + ")",
                (rs, i) -> UUID.fromString(rs.getString(1)),
                enrollmentArgs.toArray()));
        if (enrolled.size() != studentIds.size()) {
            UUID missing = studentIds.stream().filter(id -> !enrolled.contains(id)).findFirst().orElse(null);
            throw ApiException.badRequest(
                    "STUDENT_NOT_ENROLLED",
                    missing == null ? "A student is not actively enrolled in the assessment classroom" :
                            "Student " + missing + " is not actively enrolled in the assessment classroom");
        }

        List<Object> scoreQueryArgs = new ArrayList<>();
        scoreQueryArgs.add(assessmentId);
        scoreQueryArgs.addAll(studentIds);
        Map<UUID, ExistingScore> existing = jdbc.query(
                        "SELECT id,student_id,score FROM grading.student_scores " +
                                "WHERE assessment_id=? AND student_id IN (" + inClause + ")",
                        (rs, i) -> new ExistingScore(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("student_id")),
                                rs.getBigDecimal("score")),
                        scoreQueryArgs.toArray())
                .stream()
                .collect(Collectors.toMap(ExistingScore::studentId, Function.identity()));

        List<ScoreMutation> mutations = new ArrayList<>();
        for (ScoreInput input : scores) {
            ExistingScore previous = existing.get(input.studentId());
            if (previous != null && previous.score().compareTo(input.score()) == 0) {
                continue;
            }
            mutations.add(new ScoreMutation(
                    previous == null ? UUID.randomUUID() : previous.id(),
                    input.studentId(),
                    previous == null ? null : previous.score(),
                    input.score()));
        }
        if (mutations.isEmpty()) {
            return;
        }

        List<Object[]> scoreRows = mutations.stream()
                .map(m -> new Object[]{m.id(), schoolId, assessmentId, m.studentId(), m.newScore(), actor})
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO grading.student_scores(id,school_id,assessment_id,student_id,score,recorded_by) VALUES (?,?,?,?,?,?) " +
                        "ON CONFLICT(assessment_id,student_id) DO UPDATE SET score=EXCLUDED.score,recorded_by=EXCLUDED.recorded_by," +
                        "updated_at=NOW(),version=grading.student_scores.version+1",
                scoreRows);

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        List<Object[]> revisionRows = mutations.stream()
                .map(m -> new Object[]{
                        UUID.randomUUID(), schoolId, m.id(), m.previousScore(), m.newScore(), actor, reason, correlationId})
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO grading.score_revisions(id,school_id,student_score_id,previous_score,new_score,actor_user_id,reason,correlation_id) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                revisionRows);
    }

    @Transactional
    public long submit(UUID assessmentId, Long expectedVersion, UUID actor) {
        Map<String, Object> assessment = assessment(assessmentId);
        UUID schoolId = (UUID) assessment.get("school_id");
        UUID assignmentId = (UUID) assessment.get("teaching_assignment_id");
        access.requireTeacherAssignment(schoolId, actor, assignmentId);

        String status = String.valueOf(assessment.get("status"));
        long currentVersion = ((Number) assessment.get("version")).longValue();
        if ("SUBMITTED".equals(status)) {
            return currentVersion;
        }
        if ("LOCKED".equals(status)) {
            throw ApiException.conflict("ASSESSMENT_LOCKED", "Assessment is locked");
        }
        requireExpectedVersion(currentVersion, expectedVersion);

        Integer scoreCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grading.student_scores WHERE assessment_id=?",
                Integer.class, assessmentId);
        if (scoreCount == null || scoreCount == 0) {
            throw ApiException.conflict("ASSESSMENT_HAS_NO_SCORES", "Enter at least one score before submission");
        }

        int updated = jdbc.update(
                "UPDATE grading.assessments SET status='SUBMITTED',submitted_at=NOW(),submitted_by=?,version=version+1 " +
                        "WHERE id=? AND status='DRAFT' AND version=?",
                actor, assessmentId, currentVersion);
        if (updated == 0) {
            Map<String, Object> fresh = assessment(assessmentId);
            if ("SUBMITTED".equals(String.valueOf(fresh.get("status")))) {
                return ((Number) fresh.get("version")).longValue();
            }
            throw ApiException.conflict("VERSION_CONFLICT", "Assessment changed; reload before submitting");
        }
        jdbc.update(
                "UPDATE grading.student_scores SET status='SUBMITTED',updated_at=NOW() WHERE assessment_id=? AND status='DRAFT'",
                assessmentId);

        long nextVersion = currentVersion + 1;
        audit.append(
                schoolId,
                actor,
                "SUBMIT",
                "ASSESSMENT",
                assessmentId,
                Map.of("status", "DRAFT", "version", currentVersion),
                Map.of("status", "SUBMITTED", "version", nextVersion),
                "Assessment submitted");
        outbox.emit(
                schoolId,
                "assessment.submitted",
                1,
                Map.of(
                        "entityId", assessmentId,
                        "entityType", "ASSESSMENT",
                        "teachingAssignmentId", assignmentId,
                        "status", "SUBMITTED",
                        "version", nextVersion),
                List.of());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ss.student_id,ss.score,s.full_name FROM grading.student_scores ss " +
                        "JOIN academic.students s ON s.id=ss.student_id WHERE ss.assessment_id=?",
                assessmentId);
        for (Map<String, Object> row : rows) {
            UUID studentId = (UUID) row.get("student_id");
            notifications.notifyGuardians(
                    schoolId,
                    studentId,
                    "student.score.published",
                    "New score: " + assessment.get("title"),
                    row.get("full_name") + " received " + row.get("score") + " / " + assessment.get("max_score"),
                    "ASSESSMENT",
                    assessmentId,
                    Map.of(
                            "studentId", studentId,
                            "score", row.get("score"),
                            "maxScore", assessment.get("max_score"),
                            "assessmentStatus", "SUBMITTED",
                            "assessmentVersion", nextVersion));
        }
        return nextVersion;
    }

    @Transactional
    public long lock(UUID assessmentId, Long expectedVersion, UUID actor) {
        Map<String, Object> assessment = assessment(assessmentId);
        UUID schoolId = (UUID) assessment.get("school_id");
        access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN");

        String status = String.valueOf(assessment.get("status"));
        long currentVersion = ((Number) assessment.get("version")).longValue();
        if ("LOCKED".equals(status)) {
            return currentVersion;
        }
        if (!"SUBMITTED".equals(status)) {
            throw ApiException.conflict("ASSESSMENT_NOT_SUBMITTED", "Only a submitted assessment can be locked");
        }
        requireExpectedVersion(currentVersion, expectedVersion);

        int updated = jdbc.update(
                "UPDATE grading.assessments SET status='LOCKED',locked_at=NOW(),locked_by=?,version=version+1 " +
                        "WHERE id=? AND status='SUBMITTED' AND version=?",
                actor, assessmentId, currentVersion);
        if (updated == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "Assessment changed; reload before locking");
        }
        jdbc.update(
                "UPDATE grading.student_scores SET status='LOCKED',updated_at=NOW() WHERE assessment_id=? AND status='SUBMITTED'",
                assessmentId);
        long nextVersion = currentVersion + 1;
        audit.append(
                schoolId,
                actor,
                "LOCK",
                "ASSESSMENT",
                assessmentId,
                Map.of("status", "SUBMITTED", "version", currentVersion),
                Map.of("status", "LOCKED", "version", nextVersion),
                "Assessment locked");
        outbox.emit(
                schoolId,
                "assessment.locked",
                1,
                Map.of(
                        "entityId", assessmentId,
                        "entityType", "ASSESSMENT",
                        "status", "LOCKED",
                        "version", nextVersion),
                List.of());
        return nextVersion;
    }

    public List<Map<String, Object>> scores(UUID schoolId, UUID studentId, UUID actor) {
        access.requireStudentView(schoolId, actor, studentId);
        return jdbc.queryForList(
                "SELECT a.id assessment_id,a.title,a.category,a.max_score,a.weight,a.assessment_date,a.status,ss.score," +
                        "sub.name subject_name,ss.updated_at FROM grading.student_scores ss " +
                        "JOIN grading.assessments a ON a.id=ss.assessment_id " +
                        "JOIN academic.teaching_assignments ta ON ta.id=a.teaching_assignment_id " +
                        "JOIN academic.subjects sub ON sub.id=ta.subject_id " +
                        "WHERE ss.school_id=? AND ss.student_id=? AND a.status IN ('SUBMITTED','LOCKED') " +
                        "AND ss.status IN ('SUBMITTED','LOCKED') " +
                        "ORDER BY a.assessment_date DESC NULLS LAST,a.created_at DESC",
                schoolId, studentId);
    }

    public List<Map<String, Object>> assessmentScores(UUID assessmentId, UUID actor) {
        Map<String, Object> assessment = assessment(assessmentId);
        UUID schoolId = (UUID) assessment.get("school_id");
        access.requireTeacherAssignment(schoolId, actor, (UUID) assessment.get("teaching_assignment_id"));
        return jdbc.queryForList(
                "SELECT ss.id,ss.student_id,s.student_code,s.full_name,ss.score,ss.status,ss.version,ss.updated_at " +
                        "FROM grading.student_scores ss JOIN academic.students s ON s.id=ss.student_id " +
                        "WHERE ss.assessment_id=? ORDER BY s.full_name,s.id",
                assessmentId);
    }

    public List<Map<String, Object>> byAssignment(UUID schoolId, UUID assignmentId, UUID actor) {
        access.requireTeacherAssignment(schoolId, actor, assignmentId);
        return jdbc.queryForList(
                "SELECT a.*,COUNT(ss.id) score_count FROM grading.assessments a " +
                        "LEFT JOIN grading.student_scores ss ON ss.assessment_id=a.id " +
                        "WHERE a.school_id=? AND a.teaching_assignment_id=? GROUP BY a.id ORDER BY a.created_at DESC",
                schoolId, assignmentId);
    }

    public RevisionPage revisions(
            UUID assessmentId,
            int limit,
            OffsetDateTime beforeCreatedAt,
            UUID beforeId,
            UUID actor) {
        Map<String, Object> assessment = assessment(assessmentId);
        UUID schoolId = (UUID) assessment.get("school_id");
        access.requireTeacherAssignment(schoolId, actor, (UUID) assessment.get("teaching_assignment_id"));
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw ApiException.badRequest("INVALID_REVISION_CURSOR", "Both beforeCreatedAt and beforeId are required for a cursor");
        }
        int bounded = Math.max(1, Math.min(limit, MAX_REVISION_PAGE));
        StringBuilder sql = new StringBuilder(
                "SELECT r.id,r.student_score_id,ss.student_id,s.student_code,s.full_name,r.previous_score,r.new_score," +
                        "r.actor_user_id,r.reason,r.correlation_id,r.created_at " +
                        "FROM grading.score_revisions r " +
                        "JOIN grading.student_scores ss ON ss.id=r.student_score_id " +
                        "JOIN academic.students s ON s.id=ss.student_id " +
                        "WHERE r.school_id=? AND ss.assessment_id=? ");
        List<Object> args = new ArrayList<>();
        args.add(schoolId);
        args.add(assessmentId);
        if (beforeCreatedAt != null) {
            sql.append("AND (r.created_at < ? OR (r.created_at = ? AND r.id < ?)) ");
            args.add(beforeCreatedAt);
            args.add(beforeCreatedAt);
            args.add(beforeId);
        }
        sql.append("ORDER BY r.created_at DESC,r.id DESC LIMIT ?");
        args.add(bounded);

        List<RevisionItem> items = jdbc.query(
                sql.toString(),
                (rs, i) -> new RevisionItem(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("student_score_id")),
                        UUID.fromString(rs.getString("student_id")),
                        rs.getString("student_code"),
                        rs.getString("full_name"),
                        rs.getBigDecimal("previous_score"),
                        rs.getBigDecimal("new_score"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("reason"),
                        rs.getString("correlation_id"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                args.toArray());
        RevisionCursor next = null;
        if (items.size() == bounded && !items.isEmpty()) {
            RevisionItem last = items.getLast();
            next = new RevisionCursor(last.createdAt(), last.id());
        }
        return new RevisionPage(items, next);
    }

    private void validateAssessment(String title, String category, BigDecimal max, BigDecimal weight) {
        if (title == null || title.isBlank() || title.trim().length() > 255) {
            throw ApiException.badRequest("INVALID_ASSESSMENT_TITLE", "Assessment title is required and must be at most 255 characters");
        }
        if (category == null || category.isBlank() || category.trim().length() > 64) {
            throw ApiException.badRequest("INVALID_ASSESSMENT_CATEGORY", "Assessment category is required and must be at most 64 characters");
        }
        if (max == null || max.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("INVALID_MAX_SCORE", "Maximum score must be greater than zero");
        }
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("INVALID_ASSESSMENT_WEIGHT", "Assessment weight must be greater than zero");
        }
    }

    private void validateScoreBatch(List<ScoreInput> scores, BigDecimal max) {
        if (scores == null || scores.isEmpty()) {
            throw ApiException.badRequest("EMPTY_SCORE_BATCH", "At least one score is required");
        }
        if (scores.size() > MAX_SCORE_BATCH) {
            throw ApiException.badRequest("SCORE_BATCH_TOO_LARGE", "A score batch may contain at most 1000 students");
        }
        Set<UUID> seen = new LinkedHashSet<>();
        for (ScoreInput score : scores) {
            if (score == null || score.studentId() == null || score.score() == null) {
                throw ApiException.badRequest("INVALID_SCORE", "Student and score are required");
            }
            if (!seen.add(score.studentId())) {
                throw ApiException.badRequest("DUPLICATE_STUDENT_SCORE", "A student appears more than once in the score batch");
            }
            if (score.score().compareTo(BigDecimal.ZERO) < 0 || score.score().compareTo(max) > 0) {
                throw ApiException.badRequest("INVALID_SCORE", "Score is outside the assessment range");
            }
        }
    }

    private void requireDraft(Map<String, Object> assessment) {
        String status = String.valueOf(assessment.get("status"));
        if ("LOCKED".equals(status)) {
            throw ApiException.conflict("ASSESSMENT_LOCKED", "Assessment is locked");
        }
        if (!"DRAFT".equals(status)) {
            throw ApiException.conflict("ASSESSMENT_NOT_EDITABLE", "Scores can be edited only while the assessment is draft");
        }
    }

    private void requireExpectedVersion(long currentVersion, Long expectedVersion) {
        if (expectedVersion == null || currentVersion != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "Assessment changed; reload before changing workflow state");
        }
    }

    private Map<String, Object> assessment(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM grading.assessments WHERE id=?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Assessment not found");
        }
        return rows.getFirst();
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
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

    public record ScoreInput(UUID studentId, BigDecimal score) {}
    private record ExistingScore(UUID id, UUID studentId, BigDecimal score) {}
    private record ScoreMutation(UUID id, UUID studentId, BigDecimal previousScore, BigDecimal newScore) {}
    public record RevisionItem(
            UUID id,
            UUID studentScoreId,
            UUID studentId,
            String studentCode,
            String studentName,
            BigDecimal previousScore,
            BigDecimal newScore,
            UUID actorUserId,
            String reason,
            String correlationId,
            OffsetDateTime createdAt) {}
    public record RevisionCursor(OffsetDateTime beforeCreatedAt, UUID beforeId) {}
    public record RevisionPage(List<RevisionItem> items, RevisionCursor nextCursor) {}
}
