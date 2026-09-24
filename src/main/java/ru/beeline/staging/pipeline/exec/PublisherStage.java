/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.pipeline.publisher.ArtifactPublisher;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PublisherStage implements ArtifactPipelineStage {

    private final List<ArtifactPublisher> publishers;
    private final ModuleResolver          moduleResolver;
    private final PipelineRunService      pipelineRunService;
    private final PipelineRunRepository   pipelineRunRepository;

    private Map<String, ArtifactPublisher> registry;

    @PostConstruct
    void init() {
        registry = publishers.stream().collect(Collectors.toMap(ArtifactPublisher::moduleCode, p -> p));
        log.info("PublisherStage registry initialized for modules: {}", registry.keySet());
    }

    @Override
    public String stageName() {
        return "publisher";
    }

    @Override
    public void execute(Long runId) throws Exception {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        String type = run.getArtifactType();
        String uid = run.getArtifactUid();

        Long stageLogId = pipelineRunService.startStage(runId, stageName(), "rawDataRefId=" + run.getRawDataRefId());
        try {
            if (run.getRawDataRefId() == null) {
                throw new IllegalStateException("No rawDataRefId available for uid=" + uid
                        + " — adapter stage did not produce one");
            }
            String moduleCode = moduleResolver.resolve(type, stageName());
            ArtifactPublisher publisher = registry.get(moduleCode);
            if (publisher == null) {
                throw new IllegalStateException("No ArtifactPublisher registered for moduleCode=" + moduleCode);
            }

            log.info("stage=publisher, module={}, uid={}", moduleCode, uid);
            Map<String, Object> summary =
                    new HashMap<>(publisher.publish(uid, type, run.getRawDataRefId(), runId));

            pipelineRunService.completeStage(stageLogId, "published=" + summary.get("published"),
                    StageSupport.buildSummary(summary));
            pipelineRunService.completeRun(runId);
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, stageName(), e.getMessage());
            throw e;
        }
    }
}
