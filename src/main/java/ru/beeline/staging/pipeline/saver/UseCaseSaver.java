/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.dto.usecase.UseCaseDraft;
import ru.beeline.staging.repository.ImportDecisionRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.RequiredOperationVersionRow;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.StepVersionRow;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeInterface;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeOperation;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.RunBranchResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class UseCaseSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "usecase-saver";

    private static final String STATUS_REQUIRED = "required";
    private static final String STATUS_MATCHED = "matched";
    private static final String TYPE_OPERATION = "operation";
    private static final String TYPE_INTERFACE = "interface";
    private static final String CALL_STATUS_ARCHITECT_SPECIFIED = "architect_specified";
    private static final String CALL_STATUS_PLANNED = "planned";
    private static final String UNKNOWN_PRODUCT = "unknown";

    private final PipelineRunRepository      pipelineRunRepository;
    private final ImportDecisionRepository   importDecisionRepository;
    private final UseCaseCanonicalRepository canonicalRepository;
    private final UseCaseLandscapeRepository landscapeRepository;
    private final PipelineRunService         pipelineRunService;
    private final RunBranchResolver          runBranchResolver;
    private final ObjectMapper               objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Applies the reviewed UseCase draft and decisions to the canonical model"; }

    @Override
    @Transactional
    public SaveResult save(String artifactUid, String artifactType, long rawDataRefId,
                           Long runId, String canonicalSnapshotJson) throws Exception {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        if (run.getDraftJson() == null || run.getDraftJson().isBlank()) {
            throw new IllegalStateException("draft_json is empty for usecase run " + runId);
        }
        UseCaseDraft draft = objectMapper.readValue(run.getDraftJson(), UseCaseDraft.class);
        Map<String, ImportDecision> decisionsByPart = importDecisionRepository.findByRunId(runId).stream()
                .collect(Collectors.toMap(ImportDecision::partId, Function.identity(), (first, second) -> second));
        String branch = runBranchResolver.resolve(runId);
        UseCaseDraft.Header header = draft.usecase() != null
                ? draft.usecase() : new UseCaseDraft.Header(artifactUid, null, null, null);

        Long usecaseId = canonicalRepository.findOrCreateUseCase(artifactUid, header.projectCode());
        Long biStepVersionId = header.biStepCode() == null ? null
                : landscapeRepository.findBiStepVersionId(header.biStepCode(), branch).orElse(null);
        ArtifactBatch batch = pipelineRunService.createBatch(artifactUid, artifactType, runId, rawDataRefId, 0, 0, 0);

        Map<String, Object> usecaseAttributes = new LinkedHashMap<>();
        usecaseAttributes.put("code", header.code());
        usecaseAttributes.put("bi_step_code", header.biStepCode());
        Long usecaseVersionId = canonicalRepository.insertUseCaseVersion(usecaseId, biStepVersionId, artifactUid,
                header.name(), header.projectCode(), branch, json(usecaseAttributes));

        Apply apply = new Apply(runId, usecaseId, usecaseVersionId, branch);
        for (UseCaseDraft.MappedPart part : draft.mappedOrEmpty()) {
            Long callee = operationRequirement(apply, part.partId(), part.operation(), part.interfaceCode(),
                    part.container(), part.system());
            UseCaseDraft.Side caller = part.caller();
            Long callerRequirement = caller == null ? null : operationRequirement(apply, part.partId(),
                    caller.operation(), caller.interfaceCode(), caller.container(), caller.system());
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("operation_code", part.operation());
            attributes.put("interface_code", part.interfaceCode());
            attributes.put("container_code", part.container());
            attributes.put("tc_code", part.tcCode());
            attributes.put("sequence_code", part.sequenceCode());
            attributes.put("dynamic_diagram_url", part.dynamicDiagramUrl());
            attributes.put("bi_step_code", header.biStepCode());
            insertStep(apply, callerRequirement, callee, part.partId(), part.name(), part.seq(), part.scenarioType(),
                    UseCaseDraft.CALL_STATUS_CONFIRMED, part.stepType(), attributes);
        }

        for (UseCaseDraft.UnmappedPart part : draft.unmappedOrEmpty()) {
            ImportDecision decision = decisionsByPart.get(part.partId());
            if (decision == null) {
                throw new IllegalStateException("No decision for unmapped part " + part.partId() + " of run " + runId);
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("reason", part.reason());
            attributes.put("participants", part.participants());
            attributes.put("bi_step_code", header.biStepCode());

            Long callee;
            String callStatus;
            if (ImportDecision.MAP_EXISTING.equals(decision.decisionType())) {
                JsonNode target = objectMapper.readTree(decision.targetJson());
                String interfaceCode = target.path("interfaceCode").asText(null);
                String containerCode = target.path("containerCode").asText(null);
                callee = interfaceRequirement(apply, part.partId(), interfaceCode, containerCode);
                attributes.put("interface_code", interfaceCode);
                attributes.put("container_code", containerCode);
                callStatus = CALL_STATUS_ARCHITECT_SPECIFIED;
            } else if (ImportDecision.CREATE_NEW.equals(decision.decisionType())) {
                JsonNode request = objectMapper.readTree(decision.newRequestJson());
                callee = plannedRequirement(apply, part.partId(), request);
                attributes.put("interface_code", request.path("interfaceName").asText(null));
                attributes.put("container_code", request.path("containerName").asText(null));
                callStatus = CALL_STATUS_PLANNED;
                apply.plannedCreated++;
            } else {
                throw new IllegalStateException("Unknown decision type " + decision.decisionType()
                        + " for part " + part.partId() + " of run " + runId);
            }
            insertStep(apply, null, callee, part.partId(), part.name(), part.seq(), part.scenarioType(),
                    callStatus, part.stepType(), attributes);
        }

        log.info("Applied usecase uid={} run={} branch={}: usecaseVersionId={} steps={} planned={}",
                artifactUid, runId, branch, usecaseVersionId, apply.stepsSaved, apply.plannedCreated);
        return SaveResult.of(Map.of(
                "batchId", batch.getId(),
                "usecaseId", usecaseId,
                "stepsSaved", apply.stepsSaved,
                "requiredOperationsCreated", apply.plannedCreated));
    }

    private Long operationRequirement(Apply apply, String partId, String operationCode, String interfaceCode,
                                      String containerCode, String productCode) {
        LandscapeOperation found = operationCode == null ? null
                : landscapeRepository.findOperationByCode(operationCode, interfaceCode, apply.branch).orElse(null);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("operation_code", operationCode);
        attributes.put("interface_code", interfaceCode);
        attributes.put("container_code", containerCode);
        return requirement(apply, partId, TYPE_OPERATION, operationCode,
                firstNonBlank(productCode, containerCode, interfaceCode),
                found != null ? STATUS_MATCHED : STATUS_REQUIRED,
                found != null ? found.operationVersionId() : null, attributes);
    }

    private Long interfaceRequirement(Apply apply, String partId, String interfaceCode, String containerCode) {
        LandscapeInterface found = landscapeRepository.findInterface(interfaceCode, containerCode, apply.branch)
                .orElse(null);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("interface_code", interfaceCode);
        attributes.put("container_code", containerCode);
        return requirement(apply, partId, TYPE_INTERFACE, interfaceCode,
                firstNonBlank(found != null ? found.productCode() : null, containerCode),
                found != null ? STATUS_MATCHED : STATUS_REQUIRED, null, attributes);
    }

    private Long plannedRequirement(Apply apply, String partId, JsonNode request) {
        String interfaceName = request.path("interfaceName").asText(null);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("interface_code", interfaceName);
        attributes.put("container_code", request.path("containerName").asText(null));
        for (String field : List.of("productCode", "containerName", "interfaceName", "protocol", "note")) {
            attributes.put(field, request.path(field).asText(null));
        }
        return requirement(apply, partId, TYPE_INTERFACE, interfaceName,
                firstNonBlank(request.path("productCode").asText(null)), STATUS_REQUIRED, null, attributes);
    }

    private Long requirement(Apply apply, String partId, String type, String name, String productUid, String status,
                             Long operationVersionId, Map<String, Object> attributes) {
        String requirementName = name == null || name.isBlank() ? partId : name;
        Long requiredOperationId = canonicalRepository.findOrCreateRequiredOperation(
                apply.usecaseId + ":" + type + ":" + requirementName, apply.usecaseId, requirementName, type);
        return canonicalRepository.insertRequiredOperationVersion(new RequiredOperationVersionRow(
                requiredOperationId, partId, apply.runId, apply.usecaseVersionId, productUid, status,
                apply.branch, operationVersionId, json(attributes)));
    }

    private void insertStep(Apply apply, Long callerRequirement, Long calleeRequirement, String partId, String name,
                            Integer seq, String scenarioType, String callStatus, String stepType,
                            Map<String, Object> attributes) {
        canonicalRepository.insertStepVersion(new StepVersionRow(apply.usecaseVersionId, callerRequirement,
                calleeRequirement, partId, name, seq, scenarioType, callStatus, stepType, apply.branch,
                json(attributes)));
        apply.stepsSaved++;
    }

    private String json(Map<String, Object> attributes) {
        Map<String, Object> present = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (value != null) {
                present.put(key, value);
            }
        });
        try {
            return present.isEmpty() ? null : objectMapper.writeValueAsString(present);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize json_data", e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return UNKNOWN_PRODUCT;
    }

    private static final class Apply {
        private final Long runId;
        private final Long usecaseId;
        private final Long usecaseVersionId;
        private final String branch;
        private int stepsSaved;
        private int plannedCreated;

        private Apply(Long runId, Long usecaseId, Long usecaseVersionId, String branch) {
            this.runId = runId;
            this.usecaseId = usecaseId;
            this.usecaseVersionId = usecaseVersionId;
            this.branch = branch;
        }
    }
}
