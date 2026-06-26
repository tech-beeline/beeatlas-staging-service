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

    public int runForConfig(Configuration config, String batchId) {
        PipelineRun scan = pipelineRunService.startScanRun(config.getId(), config.getArtifactType(), batchId);
        Long stageLogId = pipelineRunService.startStage(scan.getId(), "pre-adapter",
                Map.of("configurationId", config.getId(), "artifactType", config.getArtifactType()));

        List<ArtifactPreAdapter.FoundArtifact> found;
        try {
            String moduleCode = moduleResolver.resolve(config.getArtifactType(), topic());
            ArtifactPreAdapter adapter = registry.get(moduleCode);
            if (adapter == null) {
                throw new IllegalStateException("No ArtifactPreAdapter registered for moduleCode=" + moduleCode);
            }
            found = adapter.scan(config);
        } catch (Exception e) {
            log.warn("Pre-adapter failed for configId={}: {}", config.getId(), e.getMessage());
            pipelineRunService.failStage(stageLogId, scan.getId(), "pre-adapter", e.getMessage());
            return 0;
        }

        List<String> uids = found.stream().map(ArtifactPreAdapter.FoundArtifact::uid).toList();
        pipelineRunService.completeStage(stageLogId,
                Map.of("foundArtifactUids", uids, "foundCount", uids.size()),
                Map.of("foundCount", uids.size()));
        pipelineRunService.completeRun(scan.getId());

        for (ArtifactPreAdapter.FoundArtifact item : found) {
            pipelineRunService.startArtifactPipeline(
                    config.getId(), config.getArtifactType(), item.uid(), batchId, scan.getId(), item.metadata());
        }
        return uids.size();
    }

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
