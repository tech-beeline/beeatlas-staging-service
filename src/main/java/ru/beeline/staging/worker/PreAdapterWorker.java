package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.pipeline.preadapter.ArtifactPreAdapter;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.SourceArtefactService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Component
public class PreAdapterWorker extends AbstractWorker {

    private final ConfigurationRepository configurationRepository;
    private final ModuleResolver          moduleResolver;
    private final PipelineRunService      pipelineRunService;
    private final SourceArtefactService   sourceArtefactService;
    private final List<ArtifactPreAdapter> preAdapters;

    private Map<String, ArtifactPreAdapter> registry;

    public PreAdapterWorker(ConfigurationRepository configurationRepository,
                             ModuleResolver moduleResolver,
                             PipelineRunService pipelineRunService,
                             SourceArtefactService sourceArtefactService,
                             List<ArtifactPreAdapter> preAdapters) {
        this.configurationRepository = configurationRepository;
        this.moduleResolver          = moduleResolver;
        this.pipelineRunService      = pipelineRunService;
        this.sourceArtefactService   = sourceArtefactService;
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
    protected List<String> variablesToFetch() {
        return List.of("configurationId", "artifactType");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) {
        Long configurationId = ((Number) task.getVariables().get("configurationId")).longValue();
        String artifactType  = (String) task.getVariables().get("artifactType");
        Configuration config = configurationRepository.findById(configurationId)
                .orElseThrow(() -> new NoSuchElementException("Configuration not found: " + configurationId));

        PipelineRun scan = pipelineRunService.createRun(null, artifactType, configurationId,
                                                        task.getProcessInstanceId(), null);
        Long stageLogId = pipelineRunService.startStage(scan.getId(), "pre-adapter", config.getArtifactType());

        List<ArtifactPreAdapter.FoundArtifact> found;
        try {
            String moduleCode = moduleResolver.resolve(artifactType, topic());
            ArtifactPreAdapter adapter = registry.get(moduleCode);
            if (adapter == null) {
                throw new IllegalStateException("No ArtifactPreAdapter registered for moduleCode=" + moduleCode);
            }
            found = adapter.scan(config);
        } catch (Exception e) {
            log.warn("Pre-adapter failed for configId={}: {}", configurationId, e.getMessage());
            pipelineRunService.failStage(stageLogId, scan.getId(), "pre-adapter", e.getMessage());
            throw new RuntimeException(e);
        }

        String foundArtifactUids = found.stream()
                .map(ArtifactPreAdapter.FoundArtifact::uid)
                .collect(Collectors.joining(","));
        pipelineRunService.completeStage(stageLogId, foundArtifactUids, Map.of("foundCount", found.size()));
        pipelineRunService.completeRun(scan.getId());

        List<String> artifactRefs = new ArrayList<>();
        for (ArtifactPreAdapter.FoundArtifact item : found) {
            PipelineRun run = pipelineRunService.createRun(
                    item.uid(), artifactType, configurationId, task.getProcessInstanceId(), scan.getId());
            sourceArtefactService.recordSeen(config, item.uid(), scan.getId(), run.getId());
            artifactRefs.add(run.getId() + "|" + item.uid());
        }
        return Map.of("artifactRefs", artifactRefs);
    }
}
