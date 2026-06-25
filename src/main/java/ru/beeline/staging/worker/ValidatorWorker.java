package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.pipeline.validator.ArtifactValidator;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.utils.GzipUtils;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ValidatorWorker extends AbstractWorker {

    private final List<ArtifactValidator> validators;
    private final RawDataRefRepository    rawDataRefRepository;
    private final ModuleResolver          moduleResolver;

    private Map<String, ArtifactValidator> registry;

    @PostConstruct
    void init() {
        registry = validators.stream().collect(Collectors.toMap(ArtifactValidator::moduleCode, v -> v));
        log.info("ValidatorWorker registry initialized for modules: {}", registry.keySet());
    }

    @Override
    protected String topic() { return "validator"; }

    @Override
    protected String workerId() { return "staging-validator-worker"; }

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
        ArtifactValidator validator = registry.get(moduleCode);
        if (validator == null) {
            log.warn("No ArtifactValidator registered for moduleCode={} — skipping validation", moduleCode);
            return null;
        }

        log.info("stage=validator, module={}, uid={}", moduleCode, uid);

        RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

        Map<String, Object> result = validator.validate(uid, GzipUtils.gunzipToString(ref.getRawContent()));
        // Modules return null/empty when there's nothing to add (no warnings) — make that an
        // explicit "valid" in output_data instead of an empty row, so monitoring shows a
        // result for every attempt, not just the ones with findings.
        return result != null ? result : Map.of("valid", true);
    }
}
