/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.pipeline.preadapter.ArtifactPreAdapter;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Not an {@link ArtifactPipelineStage} — it operates on a scan run (one per configuration tick),
 * not on a single artifact run, and produces the list of artifacts the rest of the chain runs on.
 */
@Slf4j
@Component
public class PreAdapterStage {

    private final ModuleResolver           moduleResolver;
    private final PipelineRunService       pipelineRunService;
    private final MeterRegistry            meterRegistry;
    private final List<ArtifactPreAdapter> preAdapters;

    private Map<String, ArtifactPreAdapter> registry;

    public PreAdapterStage(ModuleResolver moduleResolver, PipelineRunService pipelineRunService,
                            MeterRegistry meterRegistry, List<ArtifactPreAdapter> preAdapters) {
        this.moduleResolver = moduleResolver;
        this.pipelineRunService = pipelineRunService;
        this.meterRegistry = meterRegistry;
        this.preAdapters = preAdapters;
    }

    @PostConstruct
    void init() {
        registry = preAdapters.stream().collect(Collectors.toMap(ArtifactPreAdapter::moduleCode, p -> p));
        log.info("PreAdapterStage registry initialized for modules: {}", registry.keySet());
    }

    public String stageName() {
        return "pre-adapter";
    }

    public record ScanOutcome(Long stageLogId, List<ArtifactPreAdapter.FoundArtifact> found) {
    }

    public ScanOutcome scan(PipelineRun scan, Configuration config) {
        String artifactType = config.getArtifactType();
        Long stageLogId = pipelineRunService.startStage(scan.getId(), stageName(), artifactType);

        List<ArtifactPreAdapter.FoundArtifact> found;
        try {
            String moduleCode = moduleResolver.resolve(artifactType, stageName());
            ArtifactPreAdapter adapter = registry.get(moduleCode);
            if (adapter == null) {
                throw new IllegalStateException("No ArtifactPreAdapter registered for moduleCode=" + moduleCode);
            }
            found = adapter.scan(config);
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String rootMsg = cause.getMessage();
            log.warn("Pre-adapter failed for configId={}. Root cause: {}. Full trace:", config.getId(), rootMsg, e);
            pipelineRunService.failStage(stageLogId, scan.getId(), stageName(), rootMsg);
            meterRegistry.counter("staging_pipeline_scans_total", "artifact_type", artifactType, "status", "failed").increment();
            throw new RuntimeException(e);
        }

        meterRegistry.counter("staging_pipeline_scans_total", "artifact_type", artifactType, "status", "completed").increment();
        meterRegistry.summary("staging_pipeline_scans_artifact_count", "artifact_type", artifactType, "status", "completed")
                .record(found.size());
        return new ScanOutcome(stageLogId, found);
    }
}
