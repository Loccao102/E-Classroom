package com.eclassroom.core.school.infrastructure;

import com.eclassroom.core.school.domain.School;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SchoolRepository extends JpaRepository<School, UUID> {
    boolean existsByCode(String code);
    Optional<School> findByCode(String code);
}
