package ru.beeline.staging.worker;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.service.SparxScanService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class PreAdapterWorker extends AbstractWorker {

    private final ConfigurationRepository configurationRepository;
    private final RuntimeService          runtimeService;
    private final HistoryService          historyService;
    private final SparxScanService        sparxScanService;

    public PreAdapterWorker(ConfigurationRepository configurationRepository,
                            RuntimeService runtimeService,
                            HistoryService historyService,
                            SparxScanService sparxScanService) {
        this.configurationRepository = configurationRepository;
        this.runtimeService          = runtimeService;
        this.historyService          = historyService;
        this.sparxScanService        = sparxScanService;
    }

    @Override
    protected String topic()    { return "pre-adapter"; }
    @Override
    protected String workerId() { return "staging-pre-adapter-worker"; }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) {
        String batchId = task.getProcessInstanceId();
        log.info("Scheduler tick: batchId={}", batchId);

        List<Configuration> candidates =
                configurationRepository.findByIsActiveTrueAndScheduleIntervalSecondsIsNotNull();

        log.info("Found {} active scheduled configurations", candidates.size());

        for (Configuration config : candidates) {
            if (isAlreadyRunning(config)) {
                log.info("Skip configId={} — process already running", config.getId());
                continue;
            }
            if (!intervalElapsed(config)) {
                log.info("Skip configId={} — interval not yet elapsed", config.getId());
                continue;
            }
            publishEventsForConfig(config, batchId);
        }
        return null;
    }

    // -------------------------------------------------------------------------

    private void publishEventsForConfig(Configuration config, String batchId) {
        switch (config.getArtifactType()) {
            case "e2e-sequence"        -> publishE2ESequences(config, batchId);
            case "business-capability" -> log.warn("Pre-adapter for business-capability not implemented yet, configId={}", config.getId());
            default                    -> log.warn("Unknown artifact_type='{}' for configId={} — skipping", config.getArtifactType(), config.getId());
        }
    }

    private void publishE2ESequences(Configuration config, String batchId) {
        sparxScanService.scanAndPublishForConfig(config, batchId);
    }

    // -------------------------------------------------------------------------

    private boolean isAlreadyRunning(Configuration config) {
        long count = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .variableValueEquals("configurationId", config.getId())
                .active()
                .count();
        return count > 0;
    }

    private boolean intervalElapsed(Configuration config) {
        Duration interval = config.getScheduleInterval().orElse(Duration.ZERO);

        List<HistoricProcessInstance> lastRuns = historyService
                .createHistoricProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .variableValueEquals("configurationId", config.getId())
                .finished()
                .orderByProcessInstanceEndTime().desc()
                .listPage(0, 1);

        if (lastRuns.isEmpty()) {
            return true;
        }

        Instant lastEnd = lastRuns.get(0).getEndTime().toInstant();
        return Duration.between(lastEnd, Instant.now()).compareTo(interval) >= 0;
    }
}
