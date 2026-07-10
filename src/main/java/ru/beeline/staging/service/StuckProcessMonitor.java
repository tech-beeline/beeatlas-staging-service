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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @Value("${staging.recovery.max-incident-auto-retries:2}")
    private int maxIncidentAutoRetries;

    /** externalTaskId -> auto-heal attempts already made. Stays small in practice (Camunda deletes
     *  the ACT_RU_EXT_TASK row once the task finally completes or gets manually retried elsewhere),
     *  and resets on app restart — an acceptable trade-off for not needing new schema for this counter. */
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

    /**
     * A task with retries=0 is a Camunda incident, not a lock waiting to fire — fetchAndLock will
     * never pick it up again on its own, so an external error (e.g. the 5xx an adapter's HTTP call
     * got back) would otherwise wedge that artifact's iteration forever. Auto-heals up to
     * maxIncidentAutoRetries times (spaced by this method's own schedule, not the 5-minute
     * checkStuckProcesses cycle above), then gives up and leaves it as a permanent incident —
     * unlimited auto-retry would otherwise hide a genuinely broken product/config forever behind an
     * endless retry loop instead of surfacing it for someone to fix.
     */
    @Scheduled(fixedDelayString = "${staging.recovery.incident-retry-interval-ms:30000}")
    public void healIncidents() {
        List<ExternalTask> exhausted = externalTaskService.createExternalTaskQuery()
                .noRetriesLeft()
                .list();
        if (exhausted.isEmpty()) return;

        for (ExternalTask task : exhausted) {
            int attempts = incidentRetryAttempts.getOrDefault(task.getId(), 0);
            if (attempts >= maxIncidentAutoRetries) {
                continue;
            }

            attempts++;
            incidentRetryAttempts.put(task.getId(), attempts);
            log.warn("Auto-healing incident id={} topic={} processInstance={} (attempt {}/{})",
                    task.getId(), task.getTopicName(), task.getProcessInstanceId(), attempts, maxIncidentAutoRetries);
            try {
                externalTaskService.setRetries(task.getId(), 1);
            } catch (Exception e) {
                log.error("Failed to reset retries for task {}", task.getId(), e);
            }

            if (attempts == maxIncidentAutoRetries) {
                log.warn("Task id={} won't be auto-retried again if it fails once more — left as a permanent incident needing manual retry",
                        task.getId());
            }
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
