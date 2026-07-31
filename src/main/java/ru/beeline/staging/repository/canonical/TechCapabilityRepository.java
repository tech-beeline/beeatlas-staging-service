/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.TechCapability;

import java.util.Optional;

public interface TechCapabilityRepository extends JpaRepository<TechCapability, Long> {
    Optional<TechCapability> findByUid(String uid);
}
