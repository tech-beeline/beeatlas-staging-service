package ru.beeline.staging.worker;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.service.PipelineService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduler: fired by Camunda timer, iterates active configurations,
 * checks whether enough time has passed since last successful run,
 * and publishes a staging event for each eligible configuration.
 */
@Component
public class PreAdapterWorker extends AbstractWorker {

    @Autowired private ConfigurationRepository configurationRepository;
    @Autowired private RuntimeService          runtimeService;
    @Autowired private HistoryService          historyService;
    @Autowired private PipelineService         pipelineService;

    @Override
    protected String topic()    { return "pre-adapter"; }
    @Override
    protected String workerId() { return "staging-pre-adapter-worker"; }

    @Override
    protected void process(LockedExternalTask task) {
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
            pipelineService.publishEvent(config, batchId);
        }
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
            return true; // never ran — run now
        }

        Instant lastEnd = lastRuns.get(0).getEndTime().toInstant();
        Duration elapsed = Duration.between(lastEnd, Instant.now());
        return elapsed.compareTo(interval) >= 0;
    }
}
