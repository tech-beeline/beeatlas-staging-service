package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.ExternalTask;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StuckProcessMonitor {

    private final ExternalTaskService externalTaskService;
    private final RuntimeService      runtimeService;
    private final HistoryService      historyService;

    @Value("${staging.recovery.stuck-threshold-minutes:30}")
    private int stuckThresholdMinutes;

    @Value("${staging.recovery.auto-retry-count:3}")
    private int autoRetryCount;

    @EventListener(ApplicationStartedEvent.class)
    public void logResumedOnStartup() {
        long active = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .active()
                .count();
        if (active > 0) {
            log.info("Startup: {} artifact-pipeline-process instance(s) resumed by Camunda job executor", active);
        }
    }

    @Scheduled(fixedDelayString = "${staging.recovery.check-interval-ms:300000}")
    public void checkStuckProcesses() {
        Date threshold = Date.from(Instant.now().minus(stuckThresholdMinutes, ChronoUnit.MINUTES));

        checkStuckExternalTasks(threshold);
        checkLongRunningProcessInstances(threshold);
    }



    private void checkStuckExternalTasks(Date threshold) {
        List<ExternalTask> stuck = externalTaskService.createExternalTaskQuery()
                .lockExpirationBefore(threshold)
                .list();

        if (stuck.isEmpty()) return;

        log.warn("Found {} external task(s) locked > {} min", stuck.size(), stuckThresholdMinutes);
        for (ExternalTask task : stuck) {
            log.warn("Stuck task: id={}, topic={}, processInstance={}, retries={}",
                    task.getId(), task.getTopicName(), task.getProcessInstanceId(), task.getRetries());
            try {
                externalTaskService.setRetries(task.getId(), autoRetryCount);
                log.info("Reset retries={} for stuck task {}", autoRetryCount, task.getId());
            } catch (Exception e) {
                log.error("Failed to reset retries for task {}", task.getId(), e);
            }
        }
    }

    private void checkLongRunningProcessInstances(Date threshold) {
        List<HistoricProcessInstance> longRunning = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .startedBefore(threshold)
                .unfinished()
                .list();

        if (longRunning.isEmpty()) return;

        log.warn("Found {} process instance(s) running > {} min", longRunning.size(), stuckThresholdMinutes);
        longRunning.forEach(p ->
                log.warn("Long-running process: id={}, definition={}",
                        p.getId(), p.getProcessDefinitionId()));
    }
}
