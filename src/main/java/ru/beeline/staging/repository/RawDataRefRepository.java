package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.RawDataRef;

import java.util.Optional;

public interface RawDataRefRepository extends JpaRepository<RawDataRef, Long> {

    Optional<RawDataRef> findTopByArtifactUidOrderByLoadedAtDesc(String artifactUid);
}
