package ru.beeline.staging.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.pipeline.transformer.ArtifactTransformer;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.utils.GzipUtils;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TransformerWorker extends AbstractWorker {

    private final List<ArtifactTransformer> transformers;
    private final RawDataRefRepository      rawDataRefRepository;
    private final ObjectMapper              objectMapper;
    private final ModuleResolver            moduleResolver;

    private Map<String, ArtifactTransformer> registry;

    @PostConstruct
    void init() {
        registry = transformers.stream().collect(Collectors.toMap(ArtifactTransformer::moduleCode, t -> t));
        log.info("TransformerWorker registry initialized for modules: {}", registry.keySet());
    }

    @Override
    protected String topic() { return "transformer"; }

    @Override
    protected String workerId() { return "staging-transformer-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactType", "artifactUid", "rawDataRefId", "configurationId");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String uid  = (String) task.getVariables().get("artifactUid");
        String artifactType = (String) task.getVariables().get("artifactType");
        long rawDataRefId = ((Number) task.getVariables().get("rawDataRefId")).longValue();

        String moduleCode = moduleResolver.resolve(artifactType, topic());
        ArtifactTransformer transformer = registry.get(moduleCode);
        if (transformer == null) {
            throw new IllegalStateException("No ArtifactTransformer registered for moduleCode=" + moduleCode);
        }

        log.info("stage=transformer, module={}, uid={}", moduleCode, uid);

        RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

        Object snapshot = transformer.transform(uid, GzipUtils.gunzipToString(ref.getRawContent()));
        String snapshotJson = objectMapper.writeValueAsString(snapshot);

        ref.setCanonicalSnapshotJson(snapshotJson);
        rawDataRefRepository.save(ref);

        return Map.of("rawDataRefId", rawDataRefId, "canonicalSnapshotBytes", snapshotJson.length());
    }
}
