/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.manual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.canonical.OperationEntity;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.dto.usecase.UseCaseDraft;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.pipeline.saver.JsonDataValidator;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.StepRow;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.UseCaseVersionRow;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeInterface;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeOperation;
import ru.beeline.staging.repository.canonical.OperationRepository;
import ru.beeline.staging.repository.canonical.OperationVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.time.LocalDateTime;
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
    private static final String DECISION_CREATE_NEW = "usecase.saver.decision.create_new";
    private static final String DECISION_FAILED = "usecase.saver.decision.apply_failed";
    private static final String INTERACTION = "interaction";
    private static final String CALLEE_SIDE = "callee";
    private static final int AMBIGUITY_PROBE = 2;

    private final UseCaseCanonicalRepository canonicalRepository;
    private final UseCaseLandscapeRepository landscapeRepository;
    private final OperationRepository        operationRepository;
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
        List<UseCaseDraft.MappedPart> mapped = new ArrayList<>();
        List<UseCaseDraft.UnmappedPart> unmapped = new ArrayList<>();

        for (StepRow step : canonicalRepository.findSteps(version.id())) {
            JsonNode attributes = readJson(step.jsonData());
            if (step.calleeOperationVersionId() == null) {
                unmapped.add(new UseCaseDraft.UnmappedPart(step.extUid(), INTERACTION, step.seq(),
                        step.scenarioType(), step.stepType(), step.name(), CALLEE_SIDE,
                        textList(attributes.path("participants")), text(attributes, "reason"),
                        text(attributes, "suggestion")));
            } else {
                mapped.add(new UseCaseDraft.MappedPart(step.extUid(), INTERACTION, step.seq(),
                        step.scenarioType(), step.stepType(), step.name(),
                        step.calleeProductCode(), step.calleeContainerCode(), step.calleeInterfaceCode(),
                        step.calleeOperation(), callerSideOf(step), text(attributes, "tc_code"),
                        text(attributes, "sequence_code"), text(attributes, "dynamic_diagram_url"),
                        step.callStatus()));
            }
        }

        UseCaseDraft.Header header = new UseCaseDraft.Header(version.extUid(), version.name(),
                text(versionAttributes, "bi_step_code"), version.projectCode());
        return objectMapper.valueToTree(new UseCaseDraft(header, version.branchName(), mapped, unmapped));
    }

    @Override
    public List<String> unmappedParts(Long runId) {
        UseCaseVersionRow version = canonicalRepository.findVersionByRunId(runId).orElse(null);
        if (version == null) {
            return List.of();
        }
        return canonicalRepository.findSteps(version.id()).stream()
                .filter(step -> step.calleeOperationVersionId() == null)
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

        String branch = version.branchName();
        try {
            if (ImportDecision.MAP_EXISTING.equals(decision.decisionType())) {
                mapExisting(run, step, readJson(decision.targetJson()), branch);
            } else if (ImportDecision.CREATE_NEW.equals(decision.decisionType())) {
                createNew(run, step, readJson(decision.newRequestJson()), branch);
            } else {
                throw new PipelineRunBadRequestException("Недопустимый тип решения " + decision.decisionType()
                        + ": partId=" + decision.partId());
            }
        } catch (RuntimeException e) {
            saveNotice(run, DECISION_FAILED, "error", step.extUid(),
                    "Не удалось применить решение по части " + decision.partId(),
                    Map.of("part_id", decision.partId(), "decision_type", decision.decisionType(),
                            "reason", String.valueOf(e.getMessage())));
            throw e;
        }
    }

    private void mapExisting(PipelineRun run, StepRow step, JsonNode target, String branch) {
        String interfaceCode = text(target, "interfaceCode");
        String containerCode = text(target, "containerCode");
        LandscapeOperation operation = resolveOperation(target, interfaceCode, containerCode, branch, step);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("operation_code", operation.operation());
        attributes.put("interface_code", operation.interfaceCode());
        attributes.put("container_code", operation.containerCode());
        canonicalRepository.updateStepCallee(step.id(), operation.operationVersionId(),
                CALL_STATUS_ARCHITECT_SPECIFIED, jsonData(attributes));

        saveNotice(run, DECISION_MAP_EXISTING, "info", step.extUid(),
                "Сторона шага сопоставлена с существующей версией операции",
                Map.of("part_id", step.extUid(), "operation_version_id", operation.operationVersionId(),
                        "interface_code", String.valueOf(interfaceCode)));
        log.info("Решение map_existing применено: runId={} partId={} operationVersionId={}",
                run.getId(), step.extUid(), operation.operationVersionId());
    }

    private LandscapeOperation resolveOperation(JsonNode target, String interfaceCode, String containerCode,
                                                String branch, StepRow step) {
        if (target.hasNonNull("operationVersionId")) {
            long operationVersionId = target.get("operationVersionId").asLong();
            return new LandscapeOperation(operationVersionId, text(target, "operationCode"), interfaceCode,
                    containerCode, null);
        }
        String operationCode = text(target, "operationCode");
        if (operationCode != null) {
            return landscapeRepository.findOperationByCode(operationCode, interfaceCode, branch)
                    .orElseThrow(() -> new PipelineRunBadRequestException("Операция не найдена в ландшафте ветки "
                            + branch + ": operationCode=" + operationCode + " interfaceCode=" + interfaceCode));
        }
        List<LandscapeOperation> operations =
                landscapeRepository.findOperationsByInterface(interfaceCode, containerCode, branch, AMBIGUITY_PROBE);
        if (operations.isEmpty()) {
            throw new PipelineRunBadRequestException("У интерфейса нет операций в ландшафте ветки " + branch
                    + ": interfaceCode=" + interfaceCode + " containerCode=" + containerCode);
        }
        if (operations.size() > 1) {
            throw new PipelineRunBadRequestException("Интерфейс " + interfaceCode + " содержит несколько операций — "
                    + "требуется target.operationCode: partId=" + step.extUid());
        }
        return operations.get(0);
    }

    private void createNew(PipelineRun run, StepRow step, JsonNode request, String branch) {
        String interfaceName = text(request, "interfaceName");
        String containerName = text(request, "containerName");
        String operationName = firstNonBlank(text(request, "operationCode"), text(request, "operationName"),
                step.name(), step.extUid());
        LandscapeInterface iface = landscapeRepository.findInterface(interfaceName, containerName, branch)
                .orElse(null);
        String uid = operationUid(interfaceName, operationName);

        Map<String, Object> requirement = new LinkedHashMap<>();
        requirement.put("product_code", text(request, "productCode"));
        requirement.put("container_code", containerName);
        requirement.put("interface_code", interfaceName);
        requirement.put("protocol", text(request, "protocol"));
        requirement.put("note", text(request, "note"));
        requirement.put("planned_by_run_id", run.getId());

        OperationEntity entity = operationRepository.findByUid(uid).orElseGet(() -> {
            OperationEntity created = new OperationEntity();
            created.setUid(uid);
            created.setCreatedAt(LocalDateTime.now());
            return operationRepository.save(created);
        });

        OperationVersion version = new OperationVersion();
        version.setOperationId(entity.getId());
        version.setInterfaceVersionId(iface != null ? iface.interfaceVersionId() : null);
        version.setName(operationName);
        version.setJsonData(jsonData(requirement));
        version.setBranchName(branch);
        version.setCreatedAt(LocalDateTime.now());
        OperationVersion saved = operationVersionRepository.save(version);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("operation_code", operationName);
        attributes.put("interface_code", interfaceName);
        attributes.put("container_code", containerName);
        canonicalRepository.updateStepCallee(step.id(), saved.getId(), CALL_STATUS_PLANNED, jsonData(attributes));

        saveNotice(run, DECISION_CREATE_NEW, "info", step.extUid(), "Создана новая операция",
                Map.of("part_id", step.extUid(), "operation_uid", uid, "operation_version_id", saved.getId()));
        log.info("Решение create_new применено: runId={} partId={} operationUid={} operationVersionId={}",
                run.getId(), step.extUid(), uid, saved.getId());
    }

    private UseCaseDraft.Side callerSideOf(StepRow step) {
        return step.callerOperation() == null ? null : new UseCaseDraft.Side(step.callerProductCode(),
                step.callerContainerCode(), step.callerInterfaceCode(), step.callerOperation());
    }

    private void saveNotice(PipelineRun run, String code, String level, String entityUid, String message,
                            Map<String, Object> details) {
        if (run.getRawDataRefId() == null) {
            return;
        }
        noticeService.saveNotices(run.getRawDataRefId(), List.of(new ArtifactNotice(null, null, code, level, "match",
                run.getRawDataRefId(), "usecase_step", entityUid, null, message, jsonData(details), null, null,
                run.getArtifactUid(), null)));
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

    private static String operationUid(String interfaceName, String operationName) {
        return interfaceName == null || interfaceName.isBlank() ? operationName : interfaceName + "." + operationName;
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && !node.get(field).asText().isBlank()
                ? node.get(field).asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
