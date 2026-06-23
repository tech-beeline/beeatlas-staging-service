package ru.beeline.staging.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.MismatchingMessageCorrelationException;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Replaces the old Camunda BPMN Timer Start Event and the disabled SparxScanScheduler:
 * a single generic tick, once a minute, starts a fresh pre-adapter-process instance via
 * a message start event. All per-configuration scheduling (interval, already-running
 * checks) lives in PreAdapterWorker / the configurations table — this class only ticks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineTickScheduler {

    private final RuntimeService runtimeService;

    @Scheduled(
            initialDelayString = "${staging.scheduler.tick-initial-delay-ms:10000}",
            fixedRateString    = "${staging.scheduler.tick-interval-ms:60000}")
    public void tick() {
        try {
            runtimeService.startProcessInstanceByMessage("preadapter.tick");
        } catch (MismatchingMessageCorrelationException e) {
            // Camunda's own BPMN deployment-on-startup can still be in progress for the very
            // first tick right after boot — harmless, the next tick a minute later succeeds.
            log.warn("pre-adapter-process not deployed yet, skipping this tick: {}", e.getMessage());
        }
    }
}
