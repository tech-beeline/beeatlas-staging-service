/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.ValidateResult;
import ru.beeline.staging.pipeline.validator.ArtifactValidator;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ValidatorStage implements ArtifactPipelineStage {

    private final List<ArtifactValidator> validators;
    private final RawDataRefRepository    rawDataRefRepository;
    private final PipelineRunRepository   pipelineRunRepository;
    private final ModuleResolver          moduleResolver;
    private final PipelineRunService      pipelineRunService;

    private Map<String, ArtifactValidator> registry;

    @PostConstruct
    void init() {
        registry = validators.stream().collect(Collectors.toMap(ArtifactValidator::moduleCode, v -> v));
        log.info("ValidatorStage registry initialized for modules: {}", registry.keySet());
    }

    @Override
    public String stageName() {
        return "validator";
    }

    @Override
    public void execute(Long runId) throws Exception {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        String uid = run.getArtifactUid();
        String artifactType = run.getArtifactType();
        long rawDataRefId = run.getRawDataRefId();

        Long stageLogId = pipelineRunService.startStage(runId, stageName(), "rawDataRefId=" + rawDataRefId);
        try {
            if (pipelineRunService.isAlreadyFullyProcessed(uid, artifactType, rawDataRefId)) {
                log.info("stage=validator, uid={} — content unchanged and previously completed (rawDataRefId={}), skipping validation", uid, rawDataRefId);
                pipelineRunService.completeStage(stageLogId, "skipped: content unchanged", null);
                return;
            }

            String moduleCode = moduleResolver.resolve(artifactType, stageName());
            ArtifactValidator validator = registry.get(moduleCode);
            if (validator == null) {
                log.warn("No ArtifactValidator registered for moduleCode={} — skipping validation", moduleCode);
                pipelineRunService.completeStage(stageLogId, "skipped", null);
                return;
            }

            log.info("stage=validator, module={}, uid={}", moduleCode, uid);

            RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                    .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

            // TEMP: gzip disabled for easier manual inspection while debugging — see GzipUtils/SparxE2EAdapter.
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
                    StageSupport.buildSummary(output));
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, stageName(), e.getMessage());
            throw e;
        }
    }
}
