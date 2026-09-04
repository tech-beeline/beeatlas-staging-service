/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.ArtifactNoticeEntity;

import java.util.List;
import java.util.Optional;

public interface ArtifactNoticeRepository extends JpaRepository<ArtifactNoticeEntity, Long> {

    List<ArtifactNoticeEntity> findByNoticeTypeId(Long noticeTypeId);

    Optional<ArtifactNoticeEntity> findByRawDataContextIdAndNoticeTypeIdAndDetails(
            Long rawDataContextId, Long noticeTypeId, String details);
}
