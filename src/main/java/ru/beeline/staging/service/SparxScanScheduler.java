package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional plain Spring scheduler for the e2e Sparx scan, independent of the Camunda BPMN
 * timer (pre-adapter-process, staging.camunda.timer.pre-adapter-sparx-cycle). Disabled by
 * default — the BPMN timer is the production-driving schedule per ADR-001. Enable this only
 * in environments where you want scanning to run without Camunda's process overhead (e.g.
 * local/dev), and make sure not to also leave the BPMN timer enabled at a similarly short
 * interval, or scenarios will be scanned (and queued) twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "staging.scheduler.e2e-scan.enabled", havingValue = "true")
public class SparxScanScheduler {

    private final SparxScanService sparxScanService;

    @Scheduled(fixedDelayString = "${staging.scheduler.e2e-scan.interval-ms:21600000}")
    public void scan() {
        log.info("SparxScanScheduler tick");
        int published = sparxScanService.scanAllActiveE2EConfigurations();
        log.info("SparxScanScheduler published {} artifact(s)", published);
    }
}
