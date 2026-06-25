package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ModuleCatalogEntry;
import ru.beeline.staging.domain.PipelineDefinitionEntry;
import ru.beeline.staging.pipeline.PipelineDefinitions;
import ru.beeline.staging.pipeline.adapter.ArtifactAdapter;
import ru.beeline.staging.pipeline.preadapter.ArtifactPreAdapter;
import ru.beeline.staging.pipeline.saver.ArtifactSaver;
import ru.beeline.staging.pipeline.transformer.ArtifactTransformer;
import ru.beeline.staging.pipeline.validator.ArtifactValidator;
import ru.beeline.staging.repository.ModuleCatalogEntryRepository;
import ru.beeline.staging.repository.PipelineDefinitionEntryRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * On every startup, syncs staging.module_catalog and staging.pipeline_definitions with
 * what's actually registered in code — both are a generated reflection for visibility, never
 * a source of truth. module_catalog has no incoming FKs, so it's safely delete+reinsert.
 * pipeline_definitions IS referenced by pipeline_runs.pipeline_definition_id, so old versions
 * are kept forever and never edited — a new row (with isCurrent=true) is only appended when
 * an artifactType's module sequence actually changed since the last startup, so historical
 * runs keep pointing at the sequence that was truly in effect when they started.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModuleCatalogPublisher {

    private final List<ArtifactPreAdapter>  preAdapters;
    private final List<ArtifactAdapter>     adapters;
    private final List<ArtifactValidator>   validators;
    private final List<ArtifactTransformer> transformers;
    private final List<ArtifactSaver>       savers;
    private final PipelineDefinitions       pipelineDefinitions;

    private final ModuleCatalogEntryRepository       moduleCatalogRepository;
    private final PipelineDefinitionEntryRepository  pipelineDefinitionRepository;

    @EventListener(ApplicationStartedEvent.class)
    @Transactional
    public void publish() {
        publishModuleCatalog();
        publishPipelineDefinitions();
    }

    private void publishModuleCatalog() {
        moduleCatalogRepository.deleteAll();

        preAdapters.forEach(m  -> save("pre-adapter", m.moduleCode(), m.description()));
        adapters.forEach(m     -> save("adapter",     m.moduleCode(), m.description()));
        validators.forEach(m   -> save("validator",   m.moduleCode(), m.description()));
        transformers.forEach(m -> save("transformer", m.moduleCode(), m.description()));
        savers.forEach(m       -> save("saver",       m.moduleCode(), m.description()));

        log.info("Module catalog published: {} pre-adapter, {} adapter, {} validator, {} transformer, {} saver",
                preAdapters.size(), adapters.size(), validators.size(), transformers.size(), savers.size());
    }

    private void save(String moduleType, String moduleCode, String description) {
        ModuleCatalogEntry entry = new ModuleCatalogEntry();
        entry.setModuleCode(moduleCode);
        entry.setModuleType(moduleType);
        entry.setDescription(description);
        entry.setUpdatedAt(LocalDateTime.now());
        moduleCatalogRepository.save(entry);
    }

    private void publishPipelineDefinitions() {
        for (Map.Entry<String, Map<String, String>> e : pipelineDefinitions.all().entrySet()) {
            String artifactType = e.getKey();
            Map<String, String> moduleMap = e.getValue();

            List<Map<String, String>> sequence = PipelineDefinitions.STAGE_ORDER.stream()
                    .filter(moduleMap::containsKey)
                    .map(stage -> Map.of("stage", stage, "moduleCode", moduleMap.get(stage)))
                    .toList();

            Optional<PipelineDefinitionEntry> current =
                    pipelineDefinitionRepository.findByArtifactTypeAndCurrentTrue(artifactType);

            if (current.isPresent() && current.get().getModulesSequence().equals(sequence)) {
                continue; // unchanged since last startup — no new version needed
            }

            pipelineDefinitionRepository.clearCurrentFlag(artifactType);

            PipelineDefinitionEntry entry = new PipelineDefinitionEntry();
            entry.setArtifactType(artifactType);
            entry.setModulesSequence(sequence);
            entry.setCurrent(true);
            entry.setCreatedAt(LocalDateTime.now());
            pipelineDefinitionRepository.save(entry);

            log.info("Pipeline definition for artifactType={} changed — new version published", artifactType);
        }
    }
}
