/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.PipelineExecutionService;
import ru.beeline.staging.service.PipelineRunService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// An abandoned run's lease just expires and findResumeCandidates picks it back up on the next
// tick — no separate "unstick" step needed.
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineResumeScheduler {

    private static final int CANDIDATE_BATCH_SIZE = 50;

    private final PipelineRunRepository    pipelineRunRepository;
    private final ConfigurationRepository  configurationRepository;
    private final PipelineExecutionService pipelineExecutionService;
    private final PipelineRunService       pipelineRunService;

    @Value("${staging.recovery.max-auto-retries:3}")
    private int maxAutoRetries;

    @Scheduled(fixedDelayString = "${staging.executor.resume-poll-interval-ms:5000}")
    public void resumeInterrupted() {
        List<PipelineRun> candidates = pipelineRunRepository.findResumeCandidates(
                LocalDateTime.now(), PageRequest.of(0, CANDIDATE_BATCH_SIZE));

        for (PipelineRun run : candidates) {
            Optional<Configuration> config = configurationRepository.findById(run.getConfigurationId());
            if (config.isEmpty()) {
                log.warn("Run {} references missing configurationId={}, cannot resume", run.getId(), run.getConfigurationId());
                continue;
            }
            if (run.getArtifactUid() == null) {
                pipelineExecutionService.submitResumeScan(run, config.get());
            } else {
                pipelineExecutionService.submitArtifactChain(run.getId(), run.getArtifactType(), config.get().getCode());
            }
        }
    }

    @Scheduled(fixedDelayString = "${staging.recovery.check-interval-ms:300000}")
    public void autoRetryFailedRuns() {
        List<PipelineRun> retryable = pipelineRunRepository.findFailedRetryable(
                maxAutoRetries, PageRequest.of(0, CANDIDATE_BATCH_SIZE));

        for (PipelineRun run : retryable) {
            log.info("Auto-retrying pipelineRunId={} (attempt {}/{})", run.getId(), run.getRetryCount() + 1, maxAutoRetries);
            try {
                pipelineRunService.retryFailedRun(run.getId());
            } catch (Exception e) {
                log.warn("Auto-retry failed to requeue pipelineRunId={}", run.getId(), e);
            }
        }
    }
}
