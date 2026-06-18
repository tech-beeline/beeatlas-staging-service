package ru.beeline.staging.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.pipeline.ArtifactTransformer;
import ru.beeline.staging.pipeline.CanonicalSnapshot;
import ru.beeline.staging.repository.RawDataRefRepository;

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

    private Map<String, ArtifactTransformer> registry;

    @PostConstruct
    void init() {
        registry = transformers.stream().collect(Collectors.toMap(ArtifactTransformer::supportedType, t -> t));
        log.info("TransformerWorker registry initialized for types: {}", registry.keySet());
    }

    @Override
    protected String topic() { return "transformer"; }

    @Override
    protected String workerId() { return "staging-transformer-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactType", "artifactUid", "rawDataRefId");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        long rawDataRefId = ((Number) task.getVariables().get("rawDataRefId")).longValue();

        log.info("stage=transformer, type={}, uid={}", type, uid);

        ArtifactTransformer transformer = registry.get(type);
        if (transformer == null) {
            throw new IllegalStateException("No ArtifactTransformer registered for artifactType=" + type);
        }

        RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

        CanonicalSnapshot snapshot = transformer.transform(uid, ref.getRawContent());
        String snapshotJson = objectMapper.writeValueAsString(snapshot);

        return Map.of("canonicalSnapshotJson", snapshotJson);
    }
}
