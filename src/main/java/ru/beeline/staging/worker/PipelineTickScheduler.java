/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.PipelineExecutionService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineTickScheduler {

    private static final List<String> TERMINAL_STATUSES = List.of("completed", "failed");

    private final ConfigurationRepository  configurationRepository;
    private final PipelineRunRepository    pipelineRunRepository;
    private final PipelineExecutionService pipelineExecutionService;

    @Value("${staging.scheduler.stuck-scan-threshold-minutes:120}")
    private int stuckScanThresholdMinutes;

    @Scheduled(
            initialDelayString = "${staging.scheduler.tick-initial-delay-ms:10000}",
            fixedRateString    = "${staging.scheduler.tick-interval-ms:60000}")
    public void tick() {
        List<Configuration> candidates =
                configurationRepository.findByIsActiveTrueAndScheduleIntervalSecondsIsNotNull();
        if (candidates.isEmpty()) return;

        Map<Long, PipelineRun> activeScanByConfigId = pipelineRunRepository.findAllActiveScans().stream()
                .collect(Collectors.toMap(PipelineRun::getConfigurationId, Function.identity()));
        Map<Long, LocalDateTime> lastCompletionByConfigId = pipelineRunRepository.findLastScanCompletionPerConfig().stream()
                .collect(Collectors.toMap(
                        PipelineRunRepository.ConfigLastCompletion::getConfigId,
                        PipelineRunRepository.ConfigLastCompletion::getCompletedAt));
        Map<Long, PipelineRunRepository.ConfigActiveArtifactRuns> activeArtifactsByConfigId =
                pipelineRunRepository.findActiveArtifactRunCountsPerConfig().stream()
                        .collect(Collectors.toMap(
                                PipelineRunRepository.ConfigActiveArtifactRuns::getConfigId, Function.identity()));

        LocalDateTime now = LocalDateTime.now();
        for (Configuration config : candidates) {
            if (isStillActive(config, activeScanByConfigId.get(config.getId()), now)) continue;
            if (isPreviousArtifactRunStillDraining(config, activeArtifactsByConfigId.get(config.getId()), now)) continue;
            if (!intervalElapsed(config, lastCompletionByConfigId.get(config.getId()), now)) {
                log.info("Skip configId={} — interval not yet elapsed", config.getId());
                continue;
            }
            startScan(config);
        }
    }

    public void startScan(Configuration config) {
        pipelineExecutionService.submitScan(config);
    }

    public boolean isAlreadyRunning(Configuration config) {
        Optional<PipelineRun> active = pipelineRunRepository
                .findTopByConfigurationIdAndArtifactUidIsNullAndStatusNotInOrderByStartedAtDesc(
                        config.getId(), TERMINAL_STATUSES);
        return active.isPresent() && isStillActive(config, active.get(), LocalDateTime.now());
    }

    private boolean isStillActive(Configuration config, PipelineRun activeScan, LocalDateTime now) {
        if (activeScan == null) return false;

        LocalDateTime threshold = now.minus(stuckScanThresholdMinutes, ChronoUnit.MINUTES);
        if (activeScan.getStartedAt().isBefore(threshold)) {
            log.error("configId={}: scan run {} has been active since {} (> {} min) — treating it as stuck, "
                            + "unblocking the scheduler for this configuration instead of skipping forever.",
                    config.getId(), activeScan.getId(), activeScan.getStartedAt(), stuckScanThresholdMinutes);
            return false;
        }

        log.info("Skip configId={} — scan run {} already active", config.getId(), activeScan.getId());
        return true;
    }

    private boolean isPreviousArtifactRunStillDraining(Configuration config,
                                                        PipelineRunRepository.ConfigActiveArtifactRuns active,
                                                        LocalDateTime now) {
        if (active == null || active.getActiveCount() == null || active.getActiveCount() == 0) return false;

        LocalDateTime threshold = now.minus(stuckScanThresholdMinutes, ChronoUnit.MINUTES);
        if (active.getOldestStartedAt() != null && active.getOldestStartedAt().isBefore(threshold)) {
            log.error("configId={}: {} artifact run(s) from previous scan(s) still unfinished, oldest since {} "
                            + "(> {} min) — treating as stuck, unblocking the scheduler for this configuration "
                            + "instead of skipping forever.",
                    config.getId(), active.getActiveCount(), active.getOldestStartedAt(), stuckScanThresholdMinutes);
            return false;
        }

        log.info("Skip configId={} — {} artifact run(s) from the previous scan still unfinished (oldest since {})",
                config.getId(), active.getActiveCount(), active.getOldestStartedAt());
        return true;
    }

    private boolean intervalElapsed(Configuration config, LocalDateTime lastCompletedAt, LocalDateTime now) {
        if (lastCompletedAt == null) return true;
        Duration interval = config.getScheduleInterval().orElse(Duration.ZERO);
        return Duration.between(lastCompletedAt, now).compareTo(interval) >= 0;
    }
}
