package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.ArtifactNoticeEntity;

import java.util.List;

public interface ArtifactNoticeRepository extends JpaRepository<ArtifactNoticeEntity, Long> {

    List<ArtifactNoticeEntity> findByRawDataRefId(Long rawDataRefId);

    List<ArtifactNoticeEntity> findByNoticeTypeId(Long noticeTypeId);
}
