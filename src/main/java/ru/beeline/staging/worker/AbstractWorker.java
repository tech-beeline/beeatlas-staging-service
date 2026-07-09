package ru.beeline.staging.worker;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Pure Camunda polling — no pipeline_stage_logs bookkeeping here. That used to be automatic (keyed
 * off a "pipelineRunId" variable read before process() ran), but on the very first task of a fresh
 * iteration (Adapter) that variable doesn't exist yet at all — it's this task's own job to set it —
 * so the automatic path logged a stage under the wrong/missing run_id in addition to whatever the
 * worker itself logged — duplicate rows. Each worker now resolves its own run id and calls
 * PipelineRunService.startStage/completeStage/failStage explicitly, inside process().
 *
 * Fetched tasks are handed off to the shared pipelineWorkerExecutor instead of being processed
 * one-by-one on this method's own scheduling thread — different artifactTypes and different
 * products within the same scan (BPMN multi-instance is isSequential=false) can then run
 * concurrently, bounded by the executor's pool size rather than by this loop.
 *
 * process()'s output is completed as LOCAL variables (not global/shared ones): with parallel
 * multi-instance, several iterations' executions can resolve a same-named global variable
 * (pipelineRunId, artifactUid, rawDataRefId, ...) to the same shared ancestor scope and race to
 * update that single row — Camunda then throws OptimisticLockingException on VariableInstanceEntity.
 * Local variables are scoped strictly to the current execution, so sibling iterations never contend.
 * PreAdapterWorker overrides useLocalVariables() to keep its own output (artifactRefs) global — the
 * multi-instance's camunda:collection="artifactRefs" must see it, and pre-adapter only ever runs once
 * per scan (no concurrent writers), so there's nothing to race with.
 */
public abstract class AbstractWorker {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    protected ExternalTaskService externalTaskService;

    @Autowired
    protected ExecutorService pipelineWorkerExecutor;

    protected abstract String topic();

    protected abstract String workerId();

    protected abstract Map<String, Object> process(LockedExternalTask task) throws Exception;

    protected List<String> variablesToFetch() { return List.of(); }

    /** Override to return false for a worker whose output must stay a global/shared process variable. */
    protected boolean useLocalVariables() { return true; }

    @Scheduled(fixedDelayString = "${staging.worker.poll-interval-ms:500}")
    public void poll() {
        List<LockedExternalTask> tasks = externalTaskService
                .fetchAndLock(10, workerId())
                .topic(topic(), 30_000L)
                .variables(variablesToFetch())
                .execute();

        for (LockedExternalTask task : tasks) {
            pipelineWorkerExecutor.submit(() -> handle(task));
        }
    }

    private void handle(LockedExternalTask task) {
        try {
            Map<String, Object> outputVars = process(task);
            if (outputVars != null && !outputVars.isEmpty()) {
                if (useLocalVariables()) {
                    externalTaskService.complete(task.getId(), workerId(), Map.of(), outputVars);
                } else {
                    externalTaskService.complete(task.getId(), workerId(), outputVars);
                }
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
