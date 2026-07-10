package ru.beeline.staging.worker;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;

public abstract class AbstractWorker {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    protected ExternalTaskService externalTaskService;

    protected abstract String topic();

    protected abstract String workerId();

    protected abstract Map<String, Object> process(LockedExternalTask task) throws Exception;

    protected List<String> variablesToFetch() { return List.of(); }

    @Scheduled(fixedDelayString = "${staging.worker.poll-interval-ms:500}")
    public void poll() {
        List<LockedExternalTask> tasks = externalTaskService
                .fetchAndLock(10, workerId())
                .topic(topic(), 30_000L)
                .variables(variablesToFetch())
                .execute();

        for (LockedExternalTask task : tasks) {
            handle(task);
        }
    }

    private void handle(LockedExternalTask task) {
        try {
            Map<String, Object> outputVars = process(task);
            if (outputVars != null && !outputVars.isEmpty()) {
                externalTaskService.complete(task.getId(), workerId(), outputVars);
            } else {
                externalTaskService.complete(task.getId(), workerId());
            }
        } catch (Exception e) {
            log.error("Worker {} failed on task {}", workerId(), task.getId(), e);
            externalTaskService.handleFailure(task.getId(), workerId(), e.getMessage(), e.toString(), 0, 0L);
        }
    }

    /** Reusable by any worker for summary_json — scalar/boolean fields only, kept for quick dashboards. */
    protected static Map<String, Object> buildSummary(Map<String, Object> outputVars) {
        if (outputVars == null || outputVars.isEmpty()) return null;
        return outputVars.entrySet().stream()
                .filter(e -> e.getValue() instanceof Number || e.getValue() instanceof Boolean)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
