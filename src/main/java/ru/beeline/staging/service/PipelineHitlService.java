/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.pipelinerun.CancelPipelineRunResponse;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.repository.PipelineRunRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineHitlService {

    private static final String CANCELLED_STATUS = "cancelled";
    private static final String DEFAULT_CANCEL_REASON = "Отменено пользователем";

    private final PipelineRunRepository pipelineRunRepository;
    private final MeterRegistry meterRegistry;

    public CancelPipelineRunResponse cancel(Long runId, String reason) {
        if (runId == null || runId <= 0) {
            throw new PipelineRunBadRequestException("runId должен быть положительным числом");
        }
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new PipelineRunNotFoundException("Запуск не найден: runId=" + runId));

        String cancelReason = reason == null || reason.isBlank() ? DEFAULT_CANCEL_REASON : reason.trim();
        if (pipelineRunRepository.markCancelled(runId, cancelReason) == 0) {
            String status = pipelineRunRepository.findById(runId).map(PipelineRun::getStatus).orElse(run.getStatus());
            throw new PipelineRunConflictException(
                    "Запуск в статусе " + status + " нельзя отменить: runId=" + runId);
        }

        meterRegistry.counter("staging_pipeline_runs_total",
                "artifact_type", run.getArtifactType(), "status", CANCELLED_STATUS).increment();
        log.info("Cancelled run {} by user request: previousStatus={} reason={}", runId, run.getStatus(), cancelReason);
        return new CancelPipelineRunResponse(runId, CANCELLED_STATUS);
    }
}
