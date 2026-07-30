package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.StageTcVersion;

import java.util.List;
import java.util.Optional;

public interface StageTcVersionRepository extends JpaRepository<StageTcVersion, Long> {
    Optional<StageTcVersion> findByStageIdAndTcVersionId(Long stageId, Long tcVersionId);
    List<StageTcVersion> findByStageId(Long stageId);
}
