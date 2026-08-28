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

    // Shortest configured schedule is 15 min, so a scan still non-terminal past this is stuck, not slow.
    @Value("${staging.scheduler.stuck-scan-threshold-minutes:120}")
    private int stuckScanThresholdMinutes;

    // Batches what used to be 2 queries per config into 2 queries total, regardless of how many
    // configs are due — see V0013.
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

        LocalDateTime now = LocalDateTime.now();
        for (Configuration config : candidates) {
            if (isStillActive(config, activeScanByConfigId.get(config.getId()), now)) continue;
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

    // Optimization only, not the safety net — the DB unique index (V0011) is what actually
    // prevents two scans for the same config; this just avoids pointless pool submissions.
    // Used standalone (not from tick()'s batched path) by /admin/scan/e2e.
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

    private boolean intervalElapsed(Configuration config, LocalDateTime lastCompletedAt, LocalDateTime now) {
        if (lastCompletedAt == null) return true;
        Duration interval = config.getScheduleInterval().orElse(Duration.ZERO);
        return Duration.between(lastCompletedAt, now).compareTo(interval) >= 0;
    }
}
