/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.ValidateResult;
import ru.beeline.staging.pipeline.validator.ArtifactValidator;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.nio.charset.StandardCharsets;
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
            if (pipelineRunService.isAlreadyFullyProcessed(uid, artifactType, rawDataRefId)) {
                log.info("stage=validator, uid={} — content unchanged and previously completed (rawDataRefId={}), skipping validation", uid, rawDataRefId);
                pipelineRunService.completeStage(stageLogId, "skipped: content unchanged", null);
                return Map.of("valid", true, "skipped", true);
            }

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

            // TEMP: gzip disabled for easier manual inspection while debugging — see GzipUtils/SparxE2EAdapter.
            // ValidateResult result = validator.validate(uid, GzipUtils.gunzipToString(ref.getRawContent()));

            ValidateResult result = validator.validate(uid, new String(ref.getRawContent(), StandardCharsets.UTF_8));

            List<ArtifactNotice> saved = pipelineRunService.saveNotices(rawDataRefId, result.notices());

            long errorCount = saved.stream().filter(n -> "error".equals(n.level())).count();
            if (errorCount > 0) {
                throw new IllegalStateException("Validation failed: " + errorCount + " error notice(s) for uid=" + uid);
            }

            long warningCount = saved.stream().filter(n -> "warning".equals(n.level())).count();
            Map<String, Object> output = Map.of(
                    "valid", errorCount == 0,
                    "validationWarningsCount", warningCount,
                    "noticeCount", (long) saved.size()
            );
            pipelineRunService.completeStage(stageLogId,
                    warningCount > 0 ? "warnings=" + warningCount : "valid",
                    buildSummary(output));
            return output;
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, "validator", e.getMessage());
            throw e;
        }
    }
}
