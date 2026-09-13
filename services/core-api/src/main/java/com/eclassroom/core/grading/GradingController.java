package com.eclassroom.core.grading;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class GradingController {
    private final GradingService service;

    public GradingController(GradingService service) {
        this.service = service;
    }

    @PostMapping("/schools/{schoolId}/assessments")
    public Map<String, UUID> create(
            @PathVariable UUID schoolId,
            @RequestBody CreateAssessment request,
            Authentication authentication) {
        return Map.of(
                "id",
                service.create(
                        schoolId,
                        request.teachingAssignmentId(),
                        request.semesterId(),
                        request.title(),
                        request.category(),
                        request.maxScore(),
                        request.weight(),
                        request.assessmentDate(),
                        CurrentUser.id(authentication)));
    }

    @PutMapping("/assessments/{id}/scores")
    public void scores(
            @PathVariable UUID id,
            @RequestBody ScoreBatch request,
            Authentication authentication) {
        service.saveScores(id, request.scores(), request.reason(), CurrentUser.id(authentication));
    }

    @PostMapping("/assessments/{id}/submit")
    public Map<String, Long> submit(
            @PathVariable UUID id,
            @RequestBody(required = false) TransitionRequest request,
            Authentication authentication) {
        Long expectedVersion = request == null ? null : request.version();
        return Map.of("version", service.submit(id, expectedVersion, CurrentUser.id(authentication)));
    }

    @PostMapping("/assessments/{id}/lock")
    public Map<String, Long> lock(
            @PathVariable UUID id,
            @RequestBody(required = false) TransitionRequest request,
            Authentication authentication) {
        Long expectedVersion = request == null ? null : request.version();
        return Map.of("version", service.lock(id, expectedVersion, CurrentUser.id(authentication)));
    }

    @GetMapping("/schools/{schoolId}/students/{studentId}/scores")
    public List<Map<String, Object>> student(
            @PathVariable UUID schoolId,
            @PathVariable UUID studentId,
            Authentication authentication) {
        return service.scores(schoolId, studentId, CurrentUser.id(authentication));
    }

    @GetMapping("/schools/{schoolId}/teaching-assignments/{assignmentId}/assessments")
    public List<Map<String, Object>> list(
            @PathVariable UUID schoolId,
            @PathVariable UUID assignmentId,
            Authentication authentication) {
        return service.byAssignment(schoolId, assignmentId, CurrentUser.id(authentication));
    }

    @GetMapping("/assessments/{id}/scores")
    public List<Map<String, Object>> assessmentScores(
            @PathVariable UUID id,
            Authentication authentication) {
        return service.assessmentScores(id, CurrentUser.id(authentication));
    }

    @GetMapping("/assessments/{id}/revisions")
    public GradingService.RevisionPage revisions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) OffsetDateTime beforeCreatedAt,
            @RequestParam(required = false) UUID beforeId,
            Authentication authentication) {
        return service.revisions(id, limit, beforeCreatedAt, beforeId, CurrentUser.id(authentication));
    }

    public record CreateAssessment(
            UUID teachingAssignmentId,
            UUID semesterId,
            String title,
            String category,
            BigDecimal maxScore,
            BigDecimal weight,
            LocalDate assessmentDate) {}

    public record ScoreBatch(List<GradingService.ScoreInput> scores, String reason) {}
    public record TransitionRequest(Long version) {}
}
