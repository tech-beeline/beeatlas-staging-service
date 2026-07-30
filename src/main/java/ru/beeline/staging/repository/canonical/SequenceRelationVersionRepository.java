package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.SequenceRelationVersion;

import java.util.List;

public interface SequenceRelationVersionRepository extends JpaRepository<SequenceRelationVersion, Long> {
    List<SequenceRelationVersion> findBySequenceVersionId(Long sequenceVersionId);
}
