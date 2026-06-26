package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.adapter.ArtifactAdapter;
import ru.beeline.staging.service.ModuleResolver;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AdapterWorker extends AbstractWorker {

    private final List<ArtifactAdapter> adapters;
    private final ModuleResolver        moduleResolver;
    private Map<String, ArtifactAdapter> registry;

    public AdapterWorker(List<ArtifactAdapter> adapters, ModuleResolver moduleResolver) {
        this.adapters = adapters;
        this.moduleResolver = moduleResolver;
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
        return List.of("artifactType", "artifactUid", "configurationId", "metadataJson");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String uid  = (String) task.getVariables().get("artifactUid");
        String artifactType = (String) task.getVariables().get("artifactType");
        Long configurationId = ((Number) task.getVariables().get("configurationId")).longValue();
        String sourceId = String.valueOf(configurationId);

        String moduleCode = moduleResolver.resolve(artifactType, topic());
        ArtifactAdapter adapter = registry.get(moduleCode);
        if (adapter == null) {
            throw new IllegalStateException("No ArtifactAdapter registered for moduleCode=" + moduleCode);
        }

        log.info("stage=adapter, module={}, uid={}", moduleCode, uid);
        return adapter.load(uid, sourceId, null);
    }
}
