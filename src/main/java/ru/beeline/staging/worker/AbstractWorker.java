/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.worker;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

    @Autowired
    protected MeterRegistry meterRegistry;

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
        String artifactType = artifactTypeOf(task);
        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "completed";
        try {
            Map<String, Object> outputVars = process(task);
            if (outputVars != null && !outputVars.isEmpty()) {
                externalTaskService.complete(task.getId(), workerId(), Map.of(), outputVars);
            } else {
                externalTaskService.complete(task.getId(), workerId());
            }
        } catch (Exception e) {
            status = "failed";
            log.error("Worker {} failed on task {}", workerId(), task.getId(), e);
            meterRegistry.counter("staging_pipeline_errors_total", "stage", topic(), "artifact_type", artifactType).increment();
            if (BPMN_ERROR_TOPICS.contains(topic())) {
                externalTaskService.handleBpmnError(task.getId(), workerId(), ARTIFACT_FAILED_ERROR_CODE, e.getMessage());
            } else {
                externalTaskService.handleFailure(task.getId(), workerId(), e.getMessage(), e.toString(), 0, 0L);
            }
        } finally {
            sample.stop(Timer.builder("staging_pipeline_stage_duration_seconds")
                    .tag("stage", topic())
                    .tag("artifact_type", artifactType)
                    .tag("status", status)
                    .register(meterRegistry));
        }
    }

    /** MET-01..03 (prometheus-metrics-spec.md) tag by artifactType; not every topic fetches it as a
     *  Camunda variable (pre-adapter does), so this falls back to "unknown" rather than NPE-ing. */
    private static String artifactTypeOf(LockedExternalTask task) {
        Object value = task.getVariables().get("artifactType");
        return value != null ? value.toString() : "unknown";
    }

    /** Reusable by any worker for summary_json — scalar/boolean fields only, kept for quick dashboards. */
    protected static Map<String, Object> buildSummary(Map<String, Object> outputVars) {
        if (outputVars == null || outputVars.isEmpty()) return null;
        return outputVars.entrySet().stream()
                .filter(e -> e.getValue() instanceof Number || e.getValue() instanceof Boolean)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
