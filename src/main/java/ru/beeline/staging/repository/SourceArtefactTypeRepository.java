/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.SourceArtefactType;

import java.util.Optional;

public interface SourceArtefactTypeRepository extends JpaRepository<SourceArtefactType, Long> {

    Optional<SourceArtefactType> findByDataTypeIdAndSourceSystemId(Long dataTypeId, Long sourceSystemId);
}
