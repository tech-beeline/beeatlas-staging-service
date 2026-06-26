package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.adapter.ArtifactAdapter;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AdapterWorker extends AbstractWorker {

    private final List<ArtifactAdapter> adapters;
    private final ModuleResolver        moduleResolver;
    private final PipelineRunService    pipelineRunService;
    private Map<String, ArtifactAdapter> registry;

    public AdapterWorker(List<ArtifactAdapter> adapters, ModuleResolver moduleResolver,
                          PipelineRunService pipelineRunService) {
        this.adapters = adapters;
        this.moduleResolver = moduleResolver;
        this.pipelineRunService = pipelineRunService;
    }

    @PostConstruct
    void init() {
        registry = adapters.stream().collect(Collectors.toMap(ArtifactAdapter::moduleCode, a -> a));
        log.info("AdapterWorker registry initialized for modules: {}", registry.keySet());
    }

    @Override
    protected String topic() { return "adapter"; }

    @Override
    protected String workerId() { return "staging-adapter-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactRef", "artifactType", "configurationId");
    }

    /**
     * The first task of each multi-instance iteration — there's no pipelineRunId process
     * variable yet (it's per-artifact, the shared "pre-adapter" task that ran once for the
     * whole batch couldn't have set one), so unlike every other worker this one resolves its
     * own run from "artifactRef" (runId|uid, set by PreAdapterWorker per item) and logs its
     * own pipeline_stage_logs entry by hand; it then forwards pipelineRunId/artifactUid as
     * output variables so validator/transformer/saver — later in the same iteration — pick
     * them up through the normal AbstractWorker flow.
     */
    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String artifactRef = (String) task.getVariables().get("artifactRef");
        int sep = artifactRef.indexOf('|');
        Long runId = Long.parseLong(artifactRef.substring(0, sep));
        String uid = artifactRef.substring(sep + 1);
        String artifactType = (String) task.getVariables().get("artifactType");
        Long configurationId = ((Number) task.getVariables().get("configurationId")).longValue();
        String sourceId = String.valueOf(configurationId);

        pipelineRunService.bindExecution(runId, task.getProcessInstanceId(), task.getExecutionId());
        Long stageLogId = pipelineRunService.startStage(runId, "adapter", uid);
        try {
            String moduleCode = moduleResolver.resolve(artifactType, topic());
            ArtifactAdapter adapter = registry.get(moduleCode);
            if (adapter == null) {
                throw new IllegalStateException("No ArtifactAdapter registered for moduleCode=" + moduleCode);
            }

            log.info("stage=adapter, module={}, uid={}", moduleCode, uid);
            Map<String, Object> result = adapter.load(uid, sourceId, null);

            String rawDataRefId = result != null ? String.valueOf(result.get("rawDataRefId")) : null;
            pipelineRunService.completeStage(stageLogId, rawDataRefId, summaryOf(result));

            Map<String, Object> output = result != null ? new HashMap<>(result) : new HashMap<>();
            output.put("pipelineRunId", runId);
            output.put("artifactUid", uid);
            return output;
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, "adapter", e.getMessage());
            throw e;
        }
    }

    private static Map<String, Object> summaryOf(Map<String, Object> outputVars) {
        if (outputVars == null || outputVars.isEmpty()) return null;
        return outputVars.entrySet().stream()
                .filter(e -> e.getValue() instanceof Number || e.getValue() instanceof Boolean)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
