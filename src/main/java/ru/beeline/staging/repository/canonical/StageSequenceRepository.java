package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.StageSequence;

import java.util.List;
import java.util.Optional;

public interface StageSequenceRepository extends JpaRepository<StageSequence, Long> {
    Optional<StageSequence> findByStageIdAndSequenceVersionId(Long stageId, Long sequenceVersionId);
    List<StageSequence> findByStageId(Long stageId);
}
