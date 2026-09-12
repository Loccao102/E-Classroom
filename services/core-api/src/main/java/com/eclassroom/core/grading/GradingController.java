package com.eclassroom.core.grading;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;import java.time.LocalDate;import java.util.List;import java.util.Map;import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class GradingController {
    private final GradingService service; public GradingController(GradingService service){this.service=service;}
    @PostMapping("/schools/{schoolId}/assessments") public Map<String,UUID> create(@PathVariable UUID schoolId,@RequestBody CreateAssessment r,Authentication a){return Map.of("id",service.create(schoolId,r.teachingAssignmentId(),r.semesterId(),r.title(),r.category(),r.maxScore(),r.weight(),r.assessmentDate(),CurrentUser.id(a)));}
    @PutMapping("/assessments/{id}/scores") public void scores(@PathVariable UUID id,@RequestBody ScoreBatch r,Authentication a){service.saveScores(id,r.scores(),r.reason(),CurrentUser.id(a));}
    @PostMapping("/assessments/{id}/submit") public void submit(@PathVariable UUID id,Authentication a){service.submit(id,CurrentUser.id(a));}
    @PostMapping("/assessments/{id}/lock") public void lock(@PathVariable UUID id,Authentication a){service.lock(id,CurrentUser.id(a));}
    @GetMapping("/schools/{schoolId}/students/{studentId}/scores") public List<Map<String,Object>> student(@PathVariable UUID schoolId,@PathVariable UUID studentId,Authentication a){return service.scores(schoolId,studentId,CurrentUser.id(a));}
    @GetMapping("/schools/{schoolId}/teaching-assignments/{assignmentId}/assessments") public List<Map<String,Object>> list(@PathVariable UUID schoolId,@PathVariable UUID assignmentId,Authentication a){return service.byAssignment(schoolId,assignmentId,CurrentUser.id(a));}
    public record CreateAssessment(UUID teachingAssignmentId,UUID semesterId,String title,String category,BigDecimal maxScore,BigDecimal weight,LocalDate assessmentDate){} public record ScoreBatch(List<GradingService.ScoreInput> scores,String reason){}
}
