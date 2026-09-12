package com.eclassroom.core.school.api;

import com.eclassroom.core.school.application.SchoolService;
import com.eclassroom.core.school.domain.School;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/schools")
public class SchoolController {

    private final SchoolService schoolService;

    public SchoolController(SchoolService schoolService) {
        this.schoolService = schoolService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SchoolResponse create(@Valid @RequestBody CreateSchoolRequest request) {
        return SchoolResponse.from(
                schoolService.create(request.code(), request.name(), request.timezone()));
    }

    @GetMapping("/{schoolId}")
    public SchoolResponse get(@PathVariable UUID schoolId) {
        return SchoolResponse.from(schoolService.get(schoolId));
    }

    public record CreateSchoolRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String timezone) {
    }

    public record SchoolResponse(
            UUID id,
            String code,
            String name,
            String timezone,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        static SchoolResponse from(School school) {
            return new SchoolResponse(
                    school.getId(),
                    school.getCode(),
                    school.getName(),
                    school.getTimezone(),
                    school.getStatus(),
                    school.getCreatedAt(),
                    school.getUpdatedAt());
        }
    }
}
