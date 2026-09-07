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

    // findFirst... for the same reason as RawDataContextRepository's lookup: duplicates from before
    // find-or-create existed here are still in the table, and a unique-result query fails the stage
    // outright instead of reusing one of them (defect QA-2).
    Optional<ArtifactNoticeEntity> findFirstByRawDataContextIdAndNoticeTypeIdAndDetailsOrderByIdAsc(
            Long rawDataContextId, Long noticeTypeId, String details);
}
