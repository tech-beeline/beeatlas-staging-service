package ru.beeline.staging.worker;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;

/**
 * Pure Camunda polling — no pipeline_stage_logs bookkeeping here. That used to be automatic (keyed
 * off a "pipelineRunId" variable read before process() ran), but on the very first task of a fresh
 * iteration (Adapter) that variable doesn't exist yet at all — it's this task's own job to set it —
 * so the automatic path logged a stage under the wrong/missing run_id in addition to whatever the
 * worker itself logged — duplicate rows. Each worker now resolves its own run id and calls
 * PipelineRunService.startStage/completeStage/failStage explicitly, inside process().
 *
 * Fetched tasks are processed one-by-one, synchronously, on this method's own scheduling thread —
 * deliberately, for now. A brief attempt at running products within one scan concurrently
 * (BPMN multi-instance isSequential=false + submitting tasks to a shared executor) surfaced a real
 * bug: several sibling iterations resolving a same-named variable (pipelineRunId, artifactUid,
 * rawDataRefId, ...) to a shared ancestor scope and racing to update it — Camunda throws
 * OptimisticLockingException on VariableInstanceEntity, and worse, a losing/misdirected read could
 * make Validator/Transformer/Saver operate against a DIFFERENT artifact's run_id. Reverted back to
 * sequential (isSequential=true in the BPMN, no executor here) until that's solved properly.
 *
 * process()'s output is completed as an ordinary GLOBAL variable, same as before that parallelism
 * attempt. A "local variables" version was tried as defense-in-depth, but local scoping turned out
 * to NOT survive from one activity to the next within the same multi-instance iteration either
 * (Validator couldn't see rawDataRefId that Adapter had just set — NullPointerException) — so it
 * broke even the sequential case it was supposed to be harmless for. With isSequential=true there's
 * only one iteration executing at a time, so there's no sibling to race with global variables anyway.
 */
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
            int retries = task.getRetries() != null ? Math.max(0, task.getRetries() - 1) : 2;
            externalTaskService.handleFailure(
                    task.getId(), workerId(), e.getMessage(), e.toString(), retries, 5_000L);
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
