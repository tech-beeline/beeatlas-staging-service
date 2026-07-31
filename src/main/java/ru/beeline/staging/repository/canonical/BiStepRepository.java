/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.BiStep;

import java.util.Optional;

public interface BiStepRepository extends JpaRepository<BiStep, Long> {
    Optional<BiStep> findByUid(String uid);
}
