/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.BiStepRelationVersion;

public interface BiStepRelationVersionRepository extends JpaRepository<BiStepRelationVersion, Long> {
}
