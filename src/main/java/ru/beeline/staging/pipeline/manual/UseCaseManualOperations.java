/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.manual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.dto.usecase.UseCasePauseContext;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.pipeline.saver.JsonDataValidator;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.StepRow;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.UseCaseVersionRow;
import ru.beeline.staging.repository.canonical.OperationVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@Component
@RequiredArgsConstructor
public class UseCaseManualOperations implements ArtifactManualOperations {

    public static final String ARTIFACT_TYPE = "usecase";
    public static final String CALL_STATUS_ARCHITECT_SPECIFIED = "architect_specified";
    public static final String CALL_STATUS_PLANNED = "planned";

    private static final String DECISION_MAP_EXISTING = "usecase.saver.decision.map_existing";
    private static final String DECISION_PLANNED = "usecase.saver.decision.planned";
    private static final String DECISION_FAILED = "usecase.saver.decision.apply_failed";
    private static final String SUGGESTION = "map_existing | planned";

    private final UseCaseCanonicalRepository canonicalRepository;
    private final OperationVersionRepository operationVersionRepository;
    private final PipelineRunRepository      pipelineRunRepository;
    private final ArtifactNoticeService      noticeService;
    private final ObjectMapper               objectMapper;

    @Override
    public String artifactType() {
        return ARTIFACT_TYPE;
    }

    @Override
    public JsonNode pauseContext(Long runId) {
        UseCaseVersionRow version = canonicalRepository.findVersionByRunId(runId).orElse(null);
        if (version == null) {
            return null;
        }
        JsonNode versionAttributes = readJson(version.jsonData());
        List<UseCasePauseContext.Part> mapped = new ArrayList<>();
        List<UseCasePauseContext.Part> unmapped = new ArrayList<>();

        for (StepRow step : canonicalRepository.findSteps(version.id())) {
            JsonNode attributes = readJson(step.jsonData());
            UseCasePauseContext.Target target = new UseCasePauseContext.Target(step.id(),
                    step.calleeOperationVersionId(), step.operationType(), step.operationName(),
                    step.productAlias(), step.interfaceCode());

            if (step.callStatus() == null) {
                unmapped.add(new UseCasePauseContext.Part(step.extUid(), step.seq(), step.scenarioType(),
                        step.stepType(), step.name(), null, target,
                        UseCasePauseContext.ConnectionOperation.EMPTY, text(attributes, "reason"),
                        textOr(attributes, "suggestion", SUGGESTION)));
            } else {
                mapped.add(new UseCasePauseContext.Part(step.extUid(), step.seq(), step.scenarioType(),
                        step.stepType(), step.name(), step.callStatus(), target,
                        connectionOf(step), null, null));
            }
        }

        UseCasePauseContext.Header header = new UseCasePauseContext.Header(version.extUid(), version.name(),
                text(versionAttributes, "bi_step_code"), version.projectCode());
        return objectMapper.valueToTree(new UseCasePauseContext(header, version.branchName(), mapped, unmapped));
    }

    @Override
    public List<String> unmappedParts(Long runId) {
        UseCaseVersionRow version = canonicalRepository.findVersionByRunId(runId).orElse(null);
        if (version == null) {
            return List.of();
        }
        return canonicalRepository.findSteps(version.id()).stream()
                .filter(step -> step.callStatus() == null)
                .map(StepRow::extUid)
                .toList();
    }

    @Override
    @Transactional
    public void applyDecision(Long runId, ImportDecision decision) {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        UseCaseVersionRow version = canonicalRepository.findVersionByRunId(runId)
                .orElseThrow(() -> new PipelineRunNotFoundException(
                        "Для запуска не записана версия UseCase: runId=" + runId));
        StepRow step = canonicalRepository.findSteps(version.id()).stream()
                .filter(candidate -> decision.partId().equals(candidate.extUid()))
                .findFirst()
                .orElseThrow(() -> new PipelineRunNotFoundException("Часть не найдена в контексте паузы: runId="
                        + runId + " partId=" + decision.partId()));

        try {
            requireFreshContext(step, readJson(decision.targetJson()));
            if (ImportDecision.MAP_EXISTING.equals(decision.decisionType())) {
                mapExisting(run, step, readJson(decision.connectionOperationJson()));
            } else if (ImportDecision.PLANNED.equals(decision.decisionType())) {
                planned(run, step);
            } else {
                throw new PipelineRunBadRequestException("Недопустимый тип решения " + decision.decisionType()
                        + ": допустимы map_existing, planned");
            }
        } catch (RuntimeException e) {
            if (run.getRawDataRefId() != null) {
                noticeService.saveNoticeInNewTransaction(run.getRawDataRefId(),
                        notice(run, DECISION_FAILED, "error", step.extUid(),
                                "Не удалось применить решение по части " + decision.partId(),
                                Map.of("part_id", decision.partId(), "decision_type", decision.decisionType(),
                                        "reason", String.valueOf(e.getMessage()))));
            }
            throw e;
        }
    }

    private void requireFreshContext(StepRow step, JsonNode target) {
        Long stepVersionId = target.hasNonNull("stepVersionId") ? target.get("stepVersionId").asLong() : null;
        if (stepVersionId == null) {
            throw new PipelineRunBadRequestException("Поле target.stepVersionId обязательно: partId=" + step.extUid());
        }
        if (!stepVersionId.equals(step.id())) {
            throw new PipelineRunConflictException("Контекст паузы устарел: partId=" + step.extUid()
                    + " target.stepVersionId=" + stepVersionId + ", актуальный шаг " + step.id());
        }
        String type = text(target, "type");
        String name = text(target, "name");
        if ((type != null && !type.equalsIgnoreCase(step.operationType()))
                || (name != null && !name.equals(step.operationName()))) {
            throw new PipelineRunConflictException("Контекст паузы устарел: шаг " + step.extUid() + " сохранён как "
                    + step.operationType() + " " + step.operationName() + ", в решении " + type + " " + name);
        }
    }

    private void mapExisting(PipelineRun run, StepRow step, JsonNode connection) {
        if (!connection.hasNonNull("id")) {
            throw new PipelineRunBadRequestException("Для map_existing требуется connectionOperation.id: partId="
                    + step.extUid());
        }
        if (step.calleeOperationVersionId() == null) {
            throw new PipelineRunConflictException("У шага " + step.extUid() + " нет сохранённой операции — "
                    + "сопоставление невозможно, доступно решение planned");
        }
        int connectionOperationId = connection.get("id").asInt();
        OperationVersion operationVersion = operationVersionRepository.findById(step.calleeOperationVersionId())
                .orElseThrow(() -> new PipelineRunNotFoundException(
                        "Версия операции не найдена: id=" + step.calleeOperationVersionId()));

        operationVersion.setConnectionOperationId(connectionOperationId);
        operationVersion.setJsonData(withMatchedOperation(operationVersion.getJsonData(), connection));
        operationVersionRepository.save(operationVersion);

        canonicalRepository.updateStepDecision(step.id(), CALL_STATUS_ARCHITECT_SPECIFIED,
                jsonData(Map.of("connection_operation_id", connectionOperationId)));

        saveNotice(run, DECISION_MAP_EXISTING, "info", step.extUid(),
                "Шаг сопоставлен с архитектурной операцией",
                Map.of("part_id", step.extUid(), "operation_version_id", step.calleeOperationVersionId(),
                        "connection_operation_id", connectionOperationId));
        log.info("Решение map_existing применено: runId={} partId={} operationVersionId={} connectionOperationId={}",
                run.getId(), step.extUid(), step.calleeOperationVersionId(), connectionOperationId);
    }

    private void planned(PipelineRun run, StepRow step) {
        canonicalRepository.updateStepDecision(step.id(), CALL_STATUS_PLANNED, "{}");
        saveNotice(run, DECISION_PLANNED, "info", step.extUid(),
                "Шаг помечен как плановый — архитектурной операции нет",
                Map.of("part_id", step.extUid()));
        log.info("Решение planned применено: runId={} partId={}", run.getId(), step.extUid());
    }

    private String withMatchedOperation(String jsonData, JsonNode connection) {
        try {
            ObjectNode node = jsonData == null || jsonData.isBlank()
                    ? objectMapper.createObjectNode() : (ObjectNode) objectMapper.readTree(jsonData);
            ObjectNode matched = objectMapper.createObjectNode();
            matched.put("operationId", connection.path("id").asInt());
            putIfPresent(matched, "type", text(connection, "operationType"));
            putIfPresent(matched, "name", text(connection, "operationName"));
            putIfPresent(matched, "interfaceCode", text(connection, "interfaceCode"));
            putIfPresent(matched, "containerCode", text(connection, "containerCode"));
            putIfPresent(matched, "productAlias", text(connection, "productAlias"));
            matched.put("source", "architect_decision");
            node.set("matched_operation", matched);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось записать matched_operation", e);
        }
    }

    private UseCasePauseContext.ConnectionOperation connectionOf(StepRow step) {
        if (step.connectionOperationId() == null) {
            return UseCasePauseContext.ConnectionOperation.EMPTY;
        }
        JsonNode matched = readJson(step.matchedOperationJson());
        return new UseCasePauseContext.ConnectionOperation(step.connectionOperationId(),
                text(matched, "type"), text(matched, "name"), text(matched, "interfaceCode"),
                text(matched, "containerCode"), text(matched, "productAlias"));
    }

    private void saveNotice(PipelineRun run, String code, String level, String entityUid, String message,
                            Map<String, Object> details) {
        if (run.getRawDataRefId() == null) {
            return;
        }
        noticeService.saveNotices(run.getRawDataRefId(),
                List.of(notice(run, code, level, entityUid, message, details)));
    }

    private ArtifactNotice notice(PipelineRun run, String code, String level, String entityUid, String message,
                                  Map<String, Object> details) {
        return new ArtifactNotice(null, null, code, level, "match", run.getRawDataRefId(), "usecase_step",
                entityUid, null, message, jsonData(details), null, null, run.getArtifactUid(), null);
    }

    private String jsonData(Map<String, Object> attributes) {
        Map<String, Object> present = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (value != null) {
                present.put(key, value);
            }
        });
        String json = JsonDataValidator.toJsonData(present);
        JsonDataValidator.validate(json);
        return json == null ? "{}" : json;
    }

    private JsonNode readJson(String json) {
        try {
            return json == null || json.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Не читается JSON: " + json, e);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && !node.get(field).asText().isBlank()
                ? node.get(field).asText() : null;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value != null ? value : fallback;
    }
}
