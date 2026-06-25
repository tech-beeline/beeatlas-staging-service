package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.PipelineDefinitionEntry;

public interface PipelineDefinitionEntryRepository extends JpaRepository<PipelineDefinitionEntry, Long> {
}
