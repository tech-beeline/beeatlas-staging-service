/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.saver.ArtifactSaver;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.RawDataRefRepository;
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
public class SaverStage implements ArtifactPipelineStage {

    private final List<ArtifactSaver>   savers;
    private final ModuleResolver        moduleResolver;
    private final PipelineRunService    pipelineRunService;
    private final PipelineRunRepository pipelineRunRepository;
    private final RawDataRefRepository  rawDataRefRepository;

    private Map<String, ArtifactSaver> registry;

    @PostConstruct
    void init() {
        registry = savers.stream().collect(Collectors.toMap(ArtifactSaver::moduleCode, s -> s));
        log.info("SaverStage registry initialized for modules: {}", registry.keySet());
    }

    @Override
    public String stageName() {
        return "saver";
    }

    @Override
    public void execute(Long runId) throws Exception {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        String type = run.getArtifactType();
        String uid = run.getArtifactUid();

        Long stageLogId = pipelineRunService.startStage(runId, stageName(), "rawDataRefId=" + run.getRawDataRefId());
        try {
            if (pipelineRunService.isAlreadyCompleted(runId)) {
                log.info("Run {} already completed — skipping duplicate save for uid={}", runId, uid);
                pipelineRunService.completeStage(stageLogId, "skipped: already completed", null);
                return;
            }

            // See ValidatorStage for why this is checked here instead of unboxed before the try.
            if (run.getRawDataRefId() == null) {
                throw new IllegalStateException("No rawDataRefId available for uid=" + uid
                        + " — adapter stage did not produce one");
            }
            long rawDataRefId = run.getRawDataRefId();

            if (pipelineRunService.isAlreadyFullyProcessed(uid, type, rawDataRefId)) {
                log.info("stage=saver, uid={} — content unchanged and previously completed (rawDataRefId={}), skipping save", uid, rawDataRefId);
                pipelineRunService.completeStage(stageLogId, "skipped: content unchanged", null);
                pipelineRunService.completeRun(runId);
                return;
            }

            String moduleCode = moduleResolver.resolve(type, stageName());
            ArtifactSaver saver = registry.get(moduleCode);
            if (saver == null) {
                throw new IllegalStateException("No ArtifactSaver registered for moduleCode=" + moduleCode);
            }

            log.info("stage=saver, module={}, uid={}", moduleCode, uid);

            RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                    .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

            SaveResult saverResult = saver.save(uid, type, rawDataRefId, runId, ref.getCanonicalSnapshotJson());
            // match-notices are saved inside the saver's own transaction; saverResult.notices() is empty

            Map<String, Object> output = new HashMap<>(saverResult.summary() != null ? saverResult.summary() : Map.of());
            output.put("saved", true);

            rawDataRefRepository.save(ref);

            Object batchId = output.get("batchId");
            String outputSummary = batchId != null ? "batchId=" + batchId : "saved=true";
            pipelineRunService.completeStage(stageLogId, outputSummary, StageSupport.buildSummary(output));
            pipelineRunService.completeRun(runId);
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, stageName(), e.getMessage());
            throw e;
        }
    }
}
