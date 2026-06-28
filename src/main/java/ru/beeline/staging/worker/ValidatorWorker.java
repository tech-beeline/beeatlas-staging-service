package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.pipeline.validator.ArtifactValidator;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;
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
    private final PipelineRunService      pipelineRunService;

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
        return List.of("artifactType", "artifactUid", "rawDataRefId", "configurationId", "pipelineRunId");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String uid  = (String) task.getVariables().get("artifactUid");
        String artifactType = (String) task.getVariables().get("artifactType");
        long rawDataRefId = ((Number) task.getVariables().get("rawDataRefId")).longValue();
        Long runId = ((Number) task.getVariables().get("pipelineRunId")).longValue();

        Long stageLogId = pipelineRunService.startStage(runId, "validator", "rawDataRefId=" + rawDataRefId);
        try {
            String moduleCode = moduleResolver.resolve(artifactType, topic());
            ArtifactValidator validator = registry.get(moduleCode);
            if (validator == null) {
                log.warn("No ArtifactValidator registered for moduleCode={} — skipping validation", moduleCode);
                pipelineRunService.completeStage(stageLogId, "skipped", null);
                return null;
            }

            log.info("stage=validator, module={}, uid={}", moduleCode, uid);

            RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                    .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

            Map<String, Object> result = validator.validate(uid, GzipUtils.gunzipToString(ref.getRawContent()));
            Map<String, Object> output = result != null ? result : Map.of("valid", true);

            Object warnings = output.get("validationWarningsCount");
            String outputSummary = warnings != null ? "warnings=" + warnings : "valid";
            pipelineRunService.completeStage(stageLogId, outputSummary, buildSummary(output));
            return output;
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, "validator", e.getMessage());
            throw e;
        }
    }
}
