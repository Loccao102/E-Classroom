package com.eclassroom.core.reporting;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;import java.util.Map;import java.util.UUID;

@RestController @RequestMapping("/api/v1/schools/{schoolId}/reports")
public class ReportingController {
    private final ReportingService service;public ReportingController(ReportingService service){this.service=service;}
    @GetMapping("/dashboard") public Map<String,Object> dashboard(@PathVariable UUID schoolId,Authentication a){return service.school(schoolId,CurrentUser.id(a));}
    @GetMapping("/students/{studentId}") public Map<String,Object> student(@PathVariable UUID schoolId,@PathVariable UUID studentId,Authentication a){return service.student(schoolId,studentId,CurrentUser.id(a));}
    @GetMapping("/risks") public List<Map<String,Object>> risks(@PathVariable UUID schoolId,Authentication a){return service.risks(schoolId,CurrentUser.id(a));}
}
