/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.RawDataContextEntity;

import java.util.Optional;

public interface RawDataContextRepository extends JpaRepository<RawDataContextEntity, Long> {

    Optional<RawDataContextEntity> findByRawDataRefIdAndPosition(Long rawDataRefId, String position);
}
