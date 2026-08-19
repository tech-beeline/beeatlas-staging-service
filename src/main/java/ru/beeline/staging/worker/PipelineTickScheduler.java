package ru.beeline.staging.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineTickScheduler {

    private final ConfigurationRepository configurationRepository;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;

    /**
     * A scan is expected to finish in minutes (shortest configured schedule is 15 min for
     * e2e-sequence) — an instance still "active" in Camunda after this many minutes is stuck,
     * not slow. Without this ceiling, isAlreadyRunning() blocks every future tick for that
     * configuration forever and silently, since a hung instance never leaves the "active" set
     * (see incident: e2e-sequence data stopped updating from the 3rd with no log signal at all).
     */
    @Value("${staging.scheduler.stuck-scan-threshold-minutes:120}")
    private int stuckScanThresholdMinutes;

    @Scheduled(
            initialDelayString = "${staging.scheduler.tick-initial-delay-ms:10000}",
            fixedRateString    = "${staging.scheduler.tick-interval-ms:60000}")
    public void tick() {
        List<Configuration> candidates =
                configurationRepository.findByIsActiveTrueAndScheduleIntervalSecondsIsNotNull();

        for (Configuration config : candidates) {
            if (isAlreadyRunning(config)) {
                continue;
            }
            if (!intervalElapsed(config)) {
                log.info("Skip configId={} — interval not yet elapsed", config.getId());
                continue;
            }
            startScan(config);
        }
    }

    public ProcessInstance startScan(Configuration config) {
        Map<String, Object> variables = Map.of(
                "configurationId", config.getId(),
                "artifactType",    config.getArtifactType());
        ProcessInstance pi = runtimeService.startProcessInstanceByMessage("config.scan.ready", variables);
        log.info("Started artifact-pipeline-process for configId={}, processInstanceId={}", config.getId(), pi.getId());
        return pi;
    }

    public boolean isAlreadyRunning(Configuration config) {
        List<ProcessInstance> active = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .variableValueEquals("configurationId", config.getId())
                .active()
                .list();

        if (active.isEmpty()) {
            return false;
        }

        Date threshold = Date.from(Instant.now().minus(stuckScanThresholdMinutes, ChronoUnit.MINUTES));
        List<HistoricProcessInstance> stale = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .variableValueEquals("configurationId", config.getId())
                .unfinished()
                .startedBefore(threshold)
                .list();

        if (stale.size() >= active.size()) {
            stale.forEach(p -> log.error(
                    "configId={}: process instance {} has been active since {} (> {} min) — treating it as stuck, "
                            + "unblocking the scheduler for this configuration instead of skipping forever. "
                            + "Needs investigation in Camunda Cockpit.",
                    config.getId(), p.getId(), p.getStartTime(), stuckScanThresholdMinutes));
            return false;
        }

        log.info("Skip configId={} — {} process instance(s) already active: {}",
                config.getId(), active.size(),
                active.stream().map(ProcessInstance::getId).collect(Collectors.joining(", ")));
        return true;
    }

    private boolean intervalElapsed(Configuration config) {
        Duration interval = config.getScheduleInterval().orElse(Duration.ZERO);

        List<HistoricProcessInstance> lastRuns = historyService
                .createHistoricProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .variableValueEquals("configurationId", config.getId())
                .finished()
                .orderByProcessInstanceEndTime().desc()
                .listPage(0, 1);

        if (lastRuns.isEmpty()) {
            return true;
        }

        Instant lastEnd = lastRuns.get(0).getEndTime().toInstant();
        return Duration.between(lastEnd, Instant.now()).compareTo(interval) >= 0;
    }
}
