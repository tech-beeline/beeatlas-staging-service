/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.pipeline.PipelineDefinitions;
import ru.beeline.staging.pipeline.manual.ManualOperations;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.PipelineRunService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ManualStage implements ArtifactPipelineStage {

    private static final Set<String> DECIDED_STATUSES = Set.of("applying", "reviewing", "awaiting_review");

    private final PipelineDefinitions   pipelineDefinitions;
    private final ManualOperations      manualOperations;
    private final PipelineRunService    pipelineRunService;
    private final PipelineRunRepository pipelineRunRepository;

    @Override
    public String stageName() {
        return "manual";
    }

    @Override
    public void execute(Long runId) {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        String type = run.getArtifactType();

        if (!pipelineDefinitions.hasStage(type, stageName())) {
            return;
        }

        Long stageLogId = pipelineRunService.startStage(runId, stageName(), "status=" + run.getStatus());
        try {
            if (DECIDED_STATUSES.contains(run.getStatus())) {
                pipelineRunRepository.advanceStage(runId, run.getStatus());
                log.info("stage=manual, uid={} — решение пользователя уже принято, статус {}",
                        run.getArtifactUid(), run.getStatus());
                pipelineRunService.completeStage(stageLogId, "decision=accepted", null);
                return;
            }
            int unmapped = manualOperations.unmappedParts(type, runId).size();
            pipelineRunRepository.pause(runId, PipelineDefinitions.PAUSE_STATUS);
            log.info("stage=manual, uid={} — run {} ожидает решения пользователя в статусе {}, несмаппировано {}",
                    run.getArtifactUid(), runId, PipelineDefinitions.PAUSE_STATUS, unmapped);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("awaitingReview", true);
            summary.put("unmapped", unmapped);
            pipelineRunService.completeStage(stageLogId, "decision=awaited", summary);
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, stageName(), e.getMessage());
            throw e;
        }
    }
}
