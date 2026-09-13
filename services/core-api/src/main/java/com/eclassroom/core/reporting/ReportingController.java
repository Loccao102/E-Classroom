package com.eclassroom.core.reporting;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/schools/{schoolId}/reports")
public class ReportingController {
    private final ReportingService service;

    public ReportingController(ReportingService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(
            @PathVariable UUID schoolId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID academicYearId,
            @RequestParam(required = false) UUID semesterId,
            Authentication authentication) {
        return service.dashboard(schoolId, CurrentUser.id(authentication), from, to, academicYearId, semesterId);
    }

    @GetMapping("/school")
    public Map<String, Object> school(
            @PathVariable UUID schoolId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID academicYearId,
            @RequestParam(required = false) UUID semesterId,
            Authentication authentication) {
        return service.schoolDashboard(schoolId, CurrentUser.id(authentication), from, to, academicYearId, semesterId);
    }

    @GetMapping("/teacher")
    public Map<String, Object> teacher(
            @PathVariable UUID schoolId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID academicYearId,
            @RequestParam(required = false) UUID semesterId,
            Authentication authentication) {
        return service.teacherDashboard(schoolId, CurrentUser.id(authentication), from, to, academicYearId, semesterId);
    }

    @GetMapping("/students/{studentId}")
    public Map<String, Object> student(
            @PathVariable UUID schoolId,
            @PathVariable UUID studentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID academicYearId,
            @RequestParam(required = false) UUID semesterId,
            Authentication authentication) {
        return service.student(schoolId, studentId, CurrentUser.id(authentication), from, to, academicYearId, semesterId);
    }

    @GetMapping("/risks")
    public List<Map<String, Object>> risks(
            @PathVariable UUID schoolId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID academicYearId,
            @RequestParam(required = false) UUID semesterId,
            Authentication authentication) {
        return service.risks(schoolId, CurrentUser.id(authentication), from, to, academicYearId, semesterId);
    }
}
