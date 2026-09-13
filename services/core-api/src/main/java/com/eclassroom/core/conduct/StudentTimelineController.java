package com.eclassroom.core.conduct;

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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/schools/{schoolId}/students/{studentId}")
public class StudentTimelineController {
    private final ConductService conduct;
    private final StudentTimelineService timeline;

    public StudentTimelineController(ConductService conduct, StudentTimelineService timeline) {
        this.conduct = conduct;
        this.timeline = timeline;
    }

    @GetMapping("/timeline")
    public StudentTimelineService.TimelinePage timeline(
            @PathVariable UUID schoolId,
            @PathVariable UUID studentId,
            Authentication authentication,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) OffsetDateTime beforeOccurredAt,
            @RequestParam(required = false) UUID beforeId,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) List<String> types) {
        return timeline.page(
                schoolId, studentId, CurrentUser.id(authentication), limit,
                beforeOccurredAt, beforeId, fromDate, toDate, types);
    }

    @PostMapping("/conduct")
    public ConductService.ConductView create(
            @PathVariable UUID schoolId,
            @PathVariable UUID studentId,
            @RequestBody ConductRequest request,
            Authentication authentication) {
        return conduct.create(schoolId, studentId, request.command(), CurrentUser.id(authentication));
    }

    @PutMapping("/conduct/{id}")
    public ConductService.ConductView update(
            @PathVariable UUID schoolId,
            @PathVariable UUID studentId,
            @PathVariable UUID id,
            @RequestBody ConductUpdateRequest request,
            Authentication authentication) {
        return conduct.update(
                schoolId, studentId, id, request.version(), request.reason(), request.command(),
                CurrentUser.id(authentication));
    }

    @GetMapping("/conduct")
    public List<ConductService.ConductView> conduct(
            @PathVariable UUID schoolId,
            @PathVariable UUID studentId,
            Authentication authentication) {
        return conduct.list(schoolId, studentId, CurrentUser.id(authentication));
    }

    @GetMapping("/conduct/{id}/revisions")
    public List<Map<String, Object>> revisions(
            @PathVariable UUID schoolId,
            @PathVariable UUID studentId,
            @PathVariable UUID id,
            Authentication authentication) {
        return conduct.revisions(schoolId, studentId, id, CurrentUser.id(authentication));
    }

    public record ConductRequest(String category, String severity, String title, String body, String visibility,
                                 UUID classroomId, UUID subjectId, OffsetDateTime occurredAt) {
        ConductService.Command command() {
            return new ConductService.Command(category, severity, title, body, visibility, classroomId, subjectId, occurredAt);
        }
    }

    public record ConductUpdateRequest(long version, String reason, String category, String severity,
                                       String title, String body, String visibility, UUID classroomId,
                                       UUID subjectId, OffsetDateTime occurredAt) {
        ConductService.Command command() {
            return new ConductService.Command(category, severity, title, body, visibility, classroomId, subjectId, occurredAt);
        }
    }
}
