package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.pipeline.ArtifactValidator;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.storage.S3StorageService;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ValidatorWorker extends AbstractWorker {

    private final List<ArtifactValidator> validators;
    private final RawDataRefRepository    rawDataRefRepository;
    private final S3StorageService        s3StorageService;

    private Map<String, ArtifactValidator> registry;

    @PostConstruct
    void init() {
        registry = validators.stream().collect(Collectors.toMap(ArtifactValidator::supportedType, v -> v));
        log.info("ValidatorWorker registry initialized for types: {}", registry.keySet());
    }

    @Override
    protected String topic() { return "validator"; }

    @Override
    protected String workerId() { return "staging-validator-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactType", "artifactUid", "rawDataRefId");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        long rawDataRefId = ((Number) task.getVariables().get("rawDataRefId")).longValue();

        log.info("stage=validator, type={}, uid={}", type, uid);

        ArtifactValidator validator = registry.get(type);
        if (validator == null) {
            log.warn("No ArtifactValidator registered for artifactType={} — skipping validation", type);
            return null;
        }

        RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));
        byte[] rawBytes = s3StorageService.getGunzip(ref.getS3Key());

        return validator.validate(uid, rawBytes);
    }
}
