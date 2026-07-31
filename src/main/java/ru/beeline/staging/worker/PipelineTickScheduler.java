/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineTickScheduler {

    private final ConfigurationRepository configurationRepository;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;

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
        return runtimeService.createProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .variableValueEquals("configurationId", config.getId())
                .active()
                .count() > 0;
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
