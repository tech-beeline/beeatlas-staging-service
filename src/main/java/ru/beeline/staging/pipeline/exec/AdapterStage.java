/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.pipeline.adapter.ArtifactAdapter;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.SourceArtefactService;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdapterStage implements ArtifactPipelineStage {

    private final List<ArtifactAdapter>   adapters;
    private final ModuleResolver          moduleResolver;
    private final PipelineRunService      pipelineRunService;
    private final PipelineRunRepository   pipelineRunRepository;
    private final ConfigurationRepository configurationRepository;
    private final SourceArtefactService   sourceArtefactService;

    private Map<String, ArtifactAdapter> registry;

    @PostConstruct
    void init() {
        registry = adapters.stream().collect(Collectors.toMap(ArtifactAdapter::moduleCode, a -> a));
        log.info("AdapterStage registry initialized for modules: {}", registry.keySet());
    }

    @Override
    public String stageName() {
        return "adapter";
    }

    @Override
    public void execute(Long runId) throws Exception {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        String uid = run.getArtifactUid();
        String artifactType = run.getArtifactType();
        Long configurationId = run.getConfigurationId();
        String sourceId = String.valueOf(configurationId);

        Long stageLogId = pipelineRunService.startStage(runId, stageName(), uid);
        try {
            String moduleCode = moduleResolver.resolve(artifactType, stageName());
            ArtifactAdapter adapter = registry.get(moduleCode);
            if (adapter == null) {
                throw new IllegalStateException("No ArtifactAdapter registered for moduleCode=" + moduleCode);
            }

            log.info("stage=adapter, module={}, uid={}", moduleCode, uid);
            Map<String, Object> result = adapter.load(uid, sourceId, null);

            if (result != null && result.get("rawDataRefId") != null) {
                Long rawDataRefId = ((Number) result.get("rawDataRefId")).longValue();
                pipelineRunService.setRawDataRefId(runId, rawDataRefId);
                configurationRepository.findById(configurationId)
                        .ifPresent(config -> sourceArtefactService.recordLoaded(config, uid, rawDataRefId));
            }
            String outputSummary = result != null ? "rawDataRefId=" + result.get("rawDataRefId") : null;
            pipelineRunService.completeStage(stageLogId, outputSummary, StageSupport.buildSummary(result));
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, stageName(), e.getMessage());
            throw e;
        }
    }
}
