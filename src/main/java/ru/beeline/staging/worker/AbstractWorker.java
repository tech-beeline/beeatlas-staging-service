package ru.beeline.staging.worker;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import ru.beeline.staging.service.PipelineRunService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class AbstractWorker {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    protected ExternalTaskService externalTaskService;

    @Autowired
    private PipelineRunService pipelineRunService;

    protected abstract String topic();

    protected abstract String workerId();

    /**
     * Execute the stage business logic.
     * Return output variables to set on the process instance, or null / empty map if none.
     */
    protected abstract Map<String, Object> process(LockedExternalTask task) throws Exception;

    protected List<String> variablesToFetch() { return List.of(); }

    @Scheduled(fixedDelayString = "${staging.worker.poll-interval-ms:500}")
    public void poll() {
        List<String> vars = new ArrayList<>(variablesToFetch());
        if (!vars.contains("pipelineRunId")) {
            vars.add("pipelineRunId");
        }

        List<LockedExternalTask> tasks = externalTaskService
                .fetchAndLock(10, workerId())
                .topic(topic(), 30_000L)
                .variables(vars)
                .execute();

        for (LockedExternalTask task : tasks) {
            Long runId = extractRunId(task);
            Long stageLogId = runId != null ? pipelineRunService.startStage(runId, topic()) : null;

            try {
                Map<String, Object> outputVars = process(task);
                if (stageLogId != null) {
                    pipelineRunService.completeStage(stageLogId, buildSummary(outputVars));
                }
                if (outputVars != null && !outputVars.isEmpty()) {
                    externalTaskService.complete(task.getId(), workerId(), outputVars);
                } else {
                    externalTaskService.complete(task.getId(), workerId());
                }
            } catch (Exception e) {
                log.error("Worker {} failed on task {}", workerId(), task.getId(), e);
                if (stageLogId != null && runId != null) {
                    int retries = task.getRetries() != null ? task.getRetries() - 1 : 2;
                    if (retries <= 0) {
                        pipelineRunService.failStage(stageLogId, runId, topic(), e.getMessage());
                    } else {
                        pipelineRunService.completeStage(stageLogId,
                                Map.of("retrying", true, "error", String.valueOf(e.getMessage())));
                    }
                }
                int retries = task.getRetries() != null ? Math.max(0, task.getRetries() - 1) : 2;
                externalTaskService.handleFailure(
                        task.getId(), workerId(), e.getMessage(), e.toString(), retries, 5_000L);
            }
        }
    }

    private Long extractRunId(LockedExternalTask task) {
        Object raw = task.getVariables().get("pipelineRunId");
        if (raw == null) return null;
        try { return ((Number) raw).longValue(); } catch (Exception e) { return null; }
    }

    private Map<String, Object> buildSummary(Map<String, Object> outputVars) {
        if (outputVars == null || outputVars.isEmpty()) return null;
        // Filter out large JSON values from summary — only keep scalar metrics
        return outputVars.entrySet().stream()
                .filter(e -> e.getValue() instanceof Number || e.getValue() instanceof Boolean)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
