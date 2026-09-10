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

    @Value("${staging.recovery.max-resume-attempts:12}")
    private int maxResumeAttempts;

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

    /**
     * The backstop that makes "no run stays non-terminal indefinitely without progress" true.
     *
     * <p>{@link #resumeInterrupted()} on its own is an unbounded loop: it re-claims any run whose
     * lease expired, forever. That is correct for a run interrupted by a pod restart, and a trap
     * for one whose stage starts and never finishes — the resume path touches neither retryCount
     * nor any terminal status, so the "after max-auto-retries → failed" rule never engages and the
     * run cycles until someone notices. On FUNC two scan runs cycled for 44 hours this way, each
     * holding its configuration's one-active-scan slot the whole time, which is what stopped every
     * subsequent scan of those configurations.
     *
     * <p>resumeCount counts claims since the last completed stage, so reaching the threshold means
     * the run has genuinely moved nothing — a slow run that keeps finishing stages keeps resetting
     * its budget and is never touched here.
     */
    @Scheduled(fixedDelayString = "${staging.recovery.stall-check-interval-ms:60000}")
    public void failStalledRuns() {
        List<PipelineRun> stalled = pipelineRunRepository.findStalled(
                maxResumeAttempts, LocalDateTime.now(), PageRequest.of(0, CANDIDATE_BATCH_SIZE));

        for (PipelineRun run : stalled) {
            try {
                pipelineRunService.failStalledRun(run.getId(), run.getResumeCount(), maxAutoRetries);
            } catch (Exception e) {
                log.warn("Stall watchdog could not terminate pipelineRunId={}", run.getId(), e);
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
