package ru.beeline.staging.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.MismatchingMessageCorrelationException;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

            log.warn("pre-adapter-process not deployed yet, skipping this tick: {}", e.getMessage());
        }
    }
}
