package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.ArtifactLoader;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Universal task executor for the "loader" stage: fetches the locked External Task,
 * resolves the ArtifactLoader registered for the task's artifactType and delegates to it.
 * Adding support for a new source type is a matter of adding a new ArtifactLoader bean —
 * this class never needs to change.
 */
@Component
public class LoaderWorker extends AbstractWorker {

    private final List<ArtifactLoader> loaders;
    private Map<String, ArtifactLoader> registry;

    public LoaderWorker(List<ArtifactLoader> loaders) {
        this.loaders = loaders;
    }

    @PostConstruct
    void init() {
        registry = loaders.stream().collect(Collectors.toMap(ArtifactLoader::supportedType, l -> l));
        log.info("LoaderWorker registry initialized for types: {}", registry.keySet());
    }

    @Override
    protected String topic() { return "loader"; }

    @Override
    protected String workerId() { return "staging-loader-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactType", "artifactUid", "configurationId", "metadataJson");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        Object configIdRaw = task.getVariables().get("configurationId");
        String sourceId = configIdRaw != null ? configIdRaw.toString() : "unknown";

        ArtifactLoader loader = registry.get(type);
        if (loader == null) {
            throw new IllegalStateException("No ArtifactLoader registered for artifactType=" + type);
        }

        log.info("stage=loader, type={}, uid={}", type, uid);
        return loader.load(uid, sourceId, null);
    }
}
