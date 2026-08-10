package ru.beeline.staging.worker;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class AbstractWorker {

    private static final String ARTIFACT_FAILED_ERROR_CODE = "artifact-failed";
    private static final Set<String> BPMN_ERROR_TOPICS = Set.of("adapter", "validator", "transformer", "saver");

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    protected ExternalTaskService externalTaskService;

    @Value("${staging.worker.lock-duration-ms:30000}")
    protected long lockDurationMs;

    protected abstract String topic();

    protected abstract String workerId();

    protected abstract Map<String, Object> process(LockedExternalTask task) throws Exception;

    protected List<String> variablesToFetch() { return List.of(); }

    @Scheduled(fixedDelayString = "${staging.worker.poll-interval-ms:500}")
    public void poll() {
        List<LockedExternalTask> tasks = externalTaskService
                .fetchAndLock(10, workerId())
                .topic(topic(), lockDurationMs)
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
            if (BPMN_ERROR_TOPICS.contains(topic())) {
                externalTaskService.handleBpmnError(task.getId(), workerId(), ARTIFACT_FAILED_ERROR_CODE, e.getMessage());
            } else {
                externalTaskService.handleFailure(task.getId(), workerId(), e.getMessage(), e.toString(), 0, 0L);
            }
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
