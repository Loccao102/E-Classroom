package com.eclassroom.core.integration;

import com.eclassroom.core.shared.api.ApiException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ImportValidationService {
    private final PeopleImportValidator people;
    private final RelationshipImportValidator relationships;

    public ImportValidationService(PeopleImportValidator people, RelationshipImportValidator relationships) {
        this.people = people;
        this.relationships = relationships;
    }

    public ValidationResult validate(java.util.UUID schoolId, String type, Map<String,String> raw, Set<String> seen) {
        return switch (type) {
            case "STUDENT", "TEACHER", "GUARDIAN" -> people.validate(schoolId, type, raw, seen);
            case "GUARDIAN_LINK", "ENROLLMENT" -> relationships.validate(schoolId, type, raw, seen);
            default -> new ValidationResult(Map.of(), List.of("UNSUPPORTED_IMPORT_TYPE"), List.of());
        };
    }

    public void validateHeaders(String type, List<String> headers) {
        Set<String> h = new HashSet<>(headers);
        List<String> required = switch (type) {
            case "STUDENT" -> List.of("student_code", "full_name");
            case "TEACHER" -> List.of("teacher_code", "full_name");
            case "GUARDIAN" -> List.of("full_name");
            case "GUARDIAN_LINK" -> List.of("student_code", "relationship");
            case "ENROLLMENT" -> List.of("student_code", "academic_year_name", "classroom_code", "start_date");
            default -> throw ApiException.badRequest("IMPORT_TYPE_UNSUPPORTED", "Unsupported import type");
        };
        for (String key : required) if (!h.contains(key)) throw ApiException.badRequest("IMPORT_REQUIRED_HEADER", "Missing required column: " + key);
        if ("GUARDIAN".equals(type) && !h.contains("email") && !h.contains("phone")) throw ApiException.badRequest("IMPORT_REQUIRED_HEADER", "Guardian import requires email or phone");
        if ("GUARDIAN_LINK".equals(type) && !h.contains("guardian_email") && !h.contains("guardian_phone")) throw ApiException.badRequest("IMPORT_REQUIRED_HEADER", "Guardian link import requires guardian_email or guardian_phone");
    }

    public record ValidationResult(Map<String,String> normalized, List<String> errors, List<String> warnings) {
        public ValidationResult {
            normalized = new LinkedHashMap<>(normalized);
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
        public boolean valid() { return errors.isEmpty(); }
    }
}
