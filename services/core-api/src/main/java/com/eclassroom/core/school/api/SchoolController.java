package com.eclassroom.core.school.api;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.school.application.SchoolService;
import com.eclassroom.core.school.domain.School;
import com.eclassroom.core.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;import java.util.UUID;

@RestController @RequestMapping("/api/v1/schools")
public class SchoolController {
    private final SchoolService schoolService;private final AccessService access;
    public SchoolController(SchoolService schoolService,AccessService access){this.schoolService=schoolService;this.access=access;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public SchoolResponse create(@Valid @RequestBody CreateSchoolRequest request,Authentication a){if(!access.isPlatformAdmin(CurrentUser.id(a)))throw com.eclassroom.core.shared.api.ApiException.forbidden("Only platform administrators can create schools");return SchoolResponse.from(schoolService.create(request.code(),request.name(),request.timezone()));}
    @GetMapping("/{schoolId}") public SchoolResponse get(@PathVariable UUID schoolId,Authentication a){access.requireMembership(schoolId,CurrentUser.id(a));return SchoolResponse.from(schoolService.get(schoolId));}
    public record CreateSchoolRequest(@NotBlank String code,@NotBlank String name,@NotBlank String timezone){}
    public record SchoolResponse(UUID id,String code,String name,String timezone,String status,Instant createdAt,Instant updatedAt){static SchoolResponse from(School s){return new SchoolResponse(s.getId(),s.getCode(),s.getName(),s.getTimezone(),s.getStatus(),s.getCreatedAt(),s.getUpdatedAt());}}
}
