package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.pipeline.preadapter.ArtifactPreAdapter;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Universal task executor for the "pre-adapter" stage: on every tick (started by
 * PipelineTickScheduler, not a Camunda BPMN timer), scans all active scheduled
 * configurations and, for each one that's due, resolves its configured ArtifactPreAdapter
 * by moduleCode and delegates to it. Adding support for a new entity type is a matter of
 * adding a new ArtifactPreAdapter bean and a PipelineDefinition — this class never changes.
 */
@Component
public class PreAdapterWorker extends AbstractWorker {

    private final ConfigurationRepository configurationRepository;
    private final RuntimeService          runtimeService;
    private final HistoryService          historyService;
    private final ModuleResolver          moduleResolver;
    private final PipelineRunService      pipelineRunService;
    private final List<ArtifactPreAdapter> preAdapters;

    private Map<String, ArtifactPreAdapter> registry;

    public PreAdapterWorker(ConfigurationRepository configurationRepository,
                             RuntimeService runtimeService,
                             HistoryService historyService,
                             ModuleResolver moduleResolver,
                             PipelineRunService pipelineRunService,
                             List<ArtifactPreAdapter> preAdapters) {
        this.configurationRepository = configurationRepository;
        this.runtimeService          = runtimeService;
        this.historyService          = historyService;
        this.moduleResolver          = moduleResolver;
        this.pipelineRunService      = pipelineRunService;
        this.preAdapters             = preAdapters;
    }

    @PostConstruct
    void init() {
        registry = preAdapters.stream().collect(Collectors.toMap(ArtifactPreAdapter::moduleCode, p -> p));
        log.info("PreAdapterWorker registry initialized for modules: {}", registry.keySet());
    }

    @Override
    protected String topic()    { return "pre-adapter"; }
    @Override
    protected String workerId() { return "staging-pre-adapter-worker"; }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) {
        String batchId = task.getProcessInstanceId();
        log.info("Pre-adapter tick: batchId={}", batchId);

        List<Configuration> candidates =
                configurationRepository.findByIsActiveTrueAndScheduleIntervalSecondsIsNotNull();

        log.info("Found {} active scheduled configurations", candidates.size());

        for (Configuration config : candidates) {
            if (isAlreadyRunning(config, batchId)) {
                log.info("Skip configId={} — process already running", config.getId());
                continue;
            }
            if (!intervalElapsed(config)) {
                log.info("Skip configId={} — interval not yet elapsed", config.getId());
                continue;
            }
            runForConfig(config, batchId);
        }
        return null;
    }

    /**
     * Shared by the scheduled tick above and any manual admin trigger for a single config.
     * On success, each artifact found gets its own pipeline_run starting with an already-
     * completed "pre-adapter" stage (see PipelineRunService.startArtifactPipeline) — pre-
     * adapter lives in the same run as adapter/validator/transformer/saver for that artifact,
     * not a separate one. There's no artifact yet to attach a failure to, though, so on
     * failure (source unreachable, module not configured, ...) this records a standalone
     * pipeline_run here instead — otherwise the failure would only show up in application
     * logs/Camunda incidents, invisible in the general monitoring tables.
     */
    public int runForConfig(Configuration config, String batchId) {
        try {
            String moduleCode = moduleResolver.resolve(config.getArtifactType(), topic());
            ArtifactPreAdapter adapter = registry.get(moduleCode);
            if (adapter == null) {
                throw new IllegalStateException("No ArtifactPreAdapter registered for moduleCode=" + moduleCode);
            }
            return adapter.scanAndPublish(config, batchId);
        } catch (Exception e) {
            log.warn("Pre-adapter failed for configId={}: {}", config.getId(), e.getMessage());
            recordFailure(config, batchId, e);
            return 0;
        }
    }

    private void recordFailure(Configuration config, String batchId, Exception e) {
        List<String> modulesSequence = moduleResolver.resolveSequence(config.getArtifactType(), List.of(topic()));
        PipelineRun run = pipelineRunService.createRun(
                "pre-adapter-scan", config.getArtifactType(), config.getId(), batchId, modulesSequence);
        Long stageLogId = pipelineRunService.startStage(run.getId(), topic(), Map.of("configurationId", config.getId()));
        pipelineRunService.failStage(stageLogId, run.getId(), topic(), e.getMessage());
    }

    // -------------------------------------------------------------------------

    /** batchId is the processInstanceId of the pre-adapter task driving this tick — it must
     *  not count itself as an "already running" process for its own configuration. */
    private boolean isAlreadyRunning(Configuration config, String batchId) {
        return runtimeService.createProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .variableValueEquals("configurationId", config.getId())
                .active()
                .list()
                .stream()
                .anyMatch(p -> !p.getId().equals(batchId));
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
