/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.SourceSystem;

import java.util.Optional;

public interface SourceSystemRepository extends JpaRepository<SourceSystem, Integer> {
    Optional<SourceSystem> findByCode(String code);
}
