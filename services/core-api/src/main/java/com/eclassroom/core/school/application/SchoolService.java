package com.eclassroom.core.school.application;

import com.eclassroom.core.school.domain.School;
import com.eclassroom.core.school.infrastructure.SchoolRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SchoolService {

    private final SchoolRepository schoolRepository;

    public SchoolService(SchoolRepository schoolRepository) {
        this.schoolRepository = schoolRepository;
    }

    @Transactional
    public School create(String code, String name, String timezone) {
        String normalizedCode = code == null ? null : code.trim().toUpperCase();
        if (normalizedCode != null && schoolRepository.existsByCode(normalizedCode)) {
            throw new IllegalArgumentException("school code already exists");
        }
        return schoolRepository.save(new School(normalizedCode, name, timezone));
    }

    @Transactional(readOnly = true)
    public School get(UUID id) {
        return schoolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("school not found"));
    }
}
