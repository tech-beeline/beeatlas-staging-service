/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.ValidateResult;
import ru.beeline.staging.pipeline.StageContext;
import ru.beeline.staging.pipeline.validator.ArtifactValidator;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.repository.SourceSystemRepository;
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
    private final SourceSystemRepository  sourceSystemRepository;
    private final ObjectMapper            objectMapper;

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

        Long stageLogId = pipelineRunService.startStage(runId, stageName(), "rawDataRefId=" + run.getRawDataRefId());
        try {
            if (run.getRawDataRefId() == null) {
                throw new IllegalStateException("No rawDataRefId available for uid=" + uid
                        + " — adapter stage did not produce one");
            }
            long rawDataRefId = run.getRawDataRefId();

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

            ValidateResult result = validator.validate(uid, new String(ref.getRawContent(), StandardCharsets.UTF_8),
                    StageSupport.contextOf(run, objectMapper, sourceSystemRepository));

            List<ArtifactNotice> saved = pipelineRunService.saveNotices(rawDataRefId, result.notices());

            List<ArtifactNotice> errors = saved.stream().filter(n -> "error".equals(n.level())).toList();
            long errorCount = errors.size();
            if (errorCount > 0) {
                throw new IllegalStateException("Валидация не пройдена (" + errorCount + "): " + reasonsOf(errors));
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

    private String reasonsOf(List<ArtifactNotice> errors) {
        return errors.stream()
                .map(this::reasonOf)
                .filter(reason -> reason != null && !reason.isBlank())
                .collect(Collectors.joining("; "));
    }

    private String reasonOf(ArtifactNotice notice) {
        try {
            JsonNode details = objectMapper.readTree(notice.details() == null ? "{}" : notice.details());
            String reason = details.path("reason").asText(null);
            String line = details.hasNonNull("line") ? " (строка " + details.get("line").asInt() + ")" : "";
            return reason != null ? reason + line : notice.message();
        } catch (Exception e) {
            return notice.message();
        }
    }
}
