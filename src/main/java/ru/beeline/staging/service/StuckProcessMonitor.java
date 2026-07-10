package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.ExternalTask;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.runtime.Incident;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class StuckProcessMonitor {

    private final ExternalTaskService externalTaskService;
    private final RuntimeService      runtimeService;
    private final HistoryService      historyService;
    private final ManagementService   managementService;

    @Value("${staging.recovery.stuck-threshold-minutes:30}")
    private int stuckThresholdMinutes;

    @Value("${staging.recovery.auto-retry-count:3}")
    private int autoRetryCount;

    @Value("${staging.recovery.max-incident-auto-retries:2}")
    private int maxIncidentAutoRetries;

    private final Map<String, Integer> incidentRetryAttempts = new ConcurrentHashMap<>();

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
        List<ExternalTask> lockedStuck = externalTaskService.createExternalTaskQuery()
                .lockExpirationBefore(threshold)
                .list();
        resetRetries(lockedStuck, "locked > " + stuckThresholdMinutes + " min");
    }

    @Scheduled(fixedDelayString = "${staging.recovery.incident-retry-interval-ms:30000}")
    public void healIncidents() {
        healExternalTaskIncidents();
        healJobIncidents();
    }

    private void healExternalTaskIncidents() {
        List<ExternalTask> exhausted = externalTaskService.createExternalTaskQuery()
                .noRetriesLeft()
                .list();
        for (ExternalTask task : exhausted) {
            healOnce("externalTask", task.getId(), task.getTopicName(), task.getProcessInstanceId(),
                    () -> externalTaskService.setRetries(task.getId(), 1));
        }
    }

    private void healJobIncidents() {
        List<Incident> failedJobs = runtimeService.createIncidentQuery()
                .incidentType(Incident.FAILED_JOB_HANDLER_TYPE)
                .list();
        for (Incident incident : failedJobs) {
            String jobId = incident.getConfiguration();
            healOnce("job", jobId, incident.getActivityId(), incident.getProcessInstanceId(),
                    () -> managementService.setJobRetries(jobId, 1));
        }
    }

    private void healOnce(String kind, String id, String topicOrActivity, String processInstanceId, Runnable resetRetries) {
        int attempts = incidentRetryAttempts.getOrDefault(id, 0);
        if (attempts >= maxIncidentAutoRetries) {
            return;
        }

        attempts++;
        incidentRetryAttempts.put(id, attempts);
        log.warn("Auto-healing {} incident id={} topic/activity={} processInstance={} (attempt {}/{})",
                kind, id, topicOrActivity, processInstanceId, attempts, maxIncidentAutoRetries);
        try {
            resetRetries.run();
        } catch (Exception e) {
            log.error("Failed to reset retries for {} {}", kind, id, e);
        }

        if (attempts == maxIncidentAutoRetries) {
            log.warn("{} id={} won't be auto-retried again if it fails once more — left as a permanent incident needing manual retry",
                    kind, id);
        }
    }

    private void resetRetries(List<ExternalTask> tasks, String reason) {
        if (tasks.isEmpty()) return;

        log.warn("Found {} external task(s): {}", tasks.size(), reason);
        for (ExternalTask task : tasks) {
            log.warn("Stuck task: id={}, topic={}, processInstance={}, retries={}, reason={}",
                    task.getId(), task.getTopicName(), task.getProcessInstanceId(), task.getRetries(), reason);
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
