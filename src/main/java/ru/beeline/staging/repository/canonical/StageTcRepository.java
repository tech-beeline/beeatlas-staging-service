package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.StageTc;

import java.util.List;
import java.util.Optional;

public interface StageTcRepository extends JpaRepository<StageTc, Long> {
    Optional<StageTc> findByStageIdAndTcId(Long stageId, Long tcId);
    List<StageTc> findByStageId(Long stageId);
}
