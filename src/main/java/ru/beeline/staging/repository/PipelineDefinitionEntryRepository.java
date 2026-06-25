package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.beeline.staging.domain.PipelineDefinitionEntry;

import java.util.List;
import java.util.Optional;

public interface PipelineDefinitionEntryRepository extends JpaRepository<PipelineDefinitionEntry, Long> {

    Optional<PipelineDefinitionEntry> findByArtifactTypeAndCurrentTrue(String artifactType);

    List<PipelineDefinitionEntry> findByArtifactTypeOrderByCreatedAtDesc(String artifactType);

    @Modifying
    @Query("UPDATE PipelineDefinitionEntry e SET e.current = FALSE WHERE e.artifactType = :artifactType AND e.current = TRUE")
    void clearCurrentFlag(@Param("artifactType") String artifactType);
}
