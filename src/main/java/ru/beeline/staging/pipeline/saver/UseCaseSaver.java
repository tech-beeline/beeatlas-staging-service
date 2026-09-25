/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.canonical.ContainerVersion;
import ru.beeline.staging.domain.canonical.InterfaceVersion;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.domain.canonical.ProductVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot;
import ru.beeline.staging.pipeline.transformer.UseCaseSnapshot;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.StepVersionRow;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.RunBranchResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UseCaseSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "usecase-saver";
    public static final String UNMAPPED_SIDE = "match.usecase_step.unmapped_side";
    public static final String CALL_STATUS_CONFIRMED = "confirmed";

    private static final String SUGGESTION_BOTH = "map_existing | planned";
    private static final String SUGGESTION_PLANNED = "planned";

    private final UseCaseCanonicalRepository canonicalRepository;
    private final UseCaseLandscapeRepository landscapeRepository;
    private final ProductMatchService        productMatchService;
    private final ContainerMatchService      containerMatchService;
    private final InterfaceMatchService      interfaceMatchService;
    private final OperationMatchService      operationMatchService;
    private final PipelineRunService         pipelineRunService;
    private final RunBranchResolver          runBranchResolver;
    private final ObjectMapper               objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() {
        return "Writes the UseCase, its calls and steps to the canonical model (phase 1)";
    }

    @Override
    @Transactional
    public SaveResult save(String artifactUid, String artifactType, long rawDataRefId,
                           Long runId, String canonicalSnapshotJson) throws Exception {
        if (canonicalSnapshotJson == null || canonicalSnapshotJson.isBlank()) {
            throw new IllegalStateException("canonical_snapshot_json is empty for usecase run " + runId);
        }
        UseCaseSnapshot snapshot = objectMapper.readValue(canonicalSnapshotJson, UseCaseSnapshot.class);
        E2ESequenceSnapshot entities = snapshot.getEntities();
        String branch = runBranchResolver.resolve(runId);
        UseCaseSnapshot.Header header = snapshot.getUsecase() != null ? snapshot.getUsecase()
                : new UseCaseSnapshot.Header();

        ArtifactBatch batch = pipelineRunService.createBatch(artifactUid, artifactType, runId, rawDataRefId,
                0, entities.getInterfaces().size(), entities.getOperations().size(),
                entities.getProducts().size(), entities.getContainers().size());
        Long batchId = batch.getId();

        Map<String, OperationVersion> operationsByExtUid =
                saveEntities(entities, rawDataRefId, batchId, branch);

        Long usecaseId = canonicalRepository.findOrCreateUseCase(artifactUid, header.getProjectCode());
        Long biStepVersionId = header.getBiStepCode() == null ? null
                : landscapeRepository.findBiStepVersionId(header.getBiStepCode(), branch).orElse(null);

        Map<String, Object> usecaseAttributes = new LinkedHashMap<>();
        usecaseAttributes.put("code", header.getCode());
        usecaseAttributes.put("bi_step_code", header.getBiStepCode());
        Long usecaseVersionId = canonicalRepository.insertUseCaseVersion(usecaseId, biStepVersionId, runId,
                artifactUid, header.getName(), header.getProjectCode(), branch, json(usecaseAttributes));

        List<ArtifactNotice> notices = new ArrayList<>();
        int unmatched = 0;
        for (UseCaseSnapshot.Step step : snapshot.getSteps()) {
            OperationVersion callee = operationsByExtUid.get(step.getCalleeOperationExtUid());
            OperationVersion caller = operationsByExtUid.get(step.getCallerOperationExtUid());
            boolean matched = callee != null && callee.getConnectionOperationId() != null;

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("operation_code", step.getOperationName());
            attributes.put("operation_type", step.getOperationType());
            attributes.put("interface_code", step.getInterfaceCode());
            attributes.put("container_code", step.getProductAlias());
            attributes.put("product_alias", step.getProductAlias());
            attributes.put("bi_step_code", header.getBiStepCode());
            if (!matched) {
                unmatched++;
                attributes.put("reason", step.getReason());
                String suggestion = callee != null ? SUGGESTION_BOTH : SUGGESTION_PLANNED;
                attributes.put("suggestion", suggestion);
                notices.add(unmappedNotice(rawDataRefId, artifactUid, step.getPartId(), step.getReason(), suggestion));
            }

            canonicalRepository.insertStepVersion(new StepVersionRow(usecaseVersionId,
                    caller != null ? caller.getId() : null,
                    callee != null ? callee.getId() : null,
                    step.getPartId(), step.getName(), step.getSeq(), step.getScenarioType(),
                    matched ? CALL_STATUS_CONFIRMED : null, step.getStepType(), branch, json(attributes)));
        }

        pipelineRunService.saveNotices(rawDataRefId, notices);

        log.info("Saved usecase uid={} run={} branch={}: usecaseVersionId={} steps={} unmatched={} operations={}",
                artifactUid, runId, branch, usecaseVersionId, snapshot.getSteps().size(), unmatched,
                operationsByExtUid.size());
        return SaveResult.of(Map.of(
                "batchId", batchId,
                "usecaseId", usecaseId,
                "usecaseVersionId", usecaseVersionId,
                "stepsSaved", snapshot.getSteps().size(),
                "unmatched", unmatched));
    }

    private Map<String, OperationVersion> saveEntities(E2ESequenceSnapshot entities, long rawDataRefId,
                                                       Long batchId, String branch) {
        Map<String, ProductVersion> productsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.ProductDraft draft : entities.getProducts()) {
            productsByUid.put(draft.getUid(), productMatchService.matchOrCreate(draft.getUid(), draft.getExtUid(),
                    draft.getName(), null, null, draft.getContext(), rawDataRefId, batchId, branch));
        }

        Map<String, ContainerVersion> containersByUid = new HashMap<>();
        for (E2ESequenceSnapshot.ContainerDraft draft : entities.getContainers()) {
            ProductVersion product = productsByUid.get(draft.getProductUid());
            containersByUid.put(draft.getUid(), containerMatchService.matchOrCreate(draft.getUid(), draft.getExtUid(),
                    draft.getName(), null, null, null, product != null ? product.getId() : null,
                    draft.getContext(), rawDataRefId, batchId, branch));
        }

        Map<String, InterfaceVersion> interfacesByUid = new HashMap<>();
        for (E2ESequenceSnapshot.InterfaceDraft draft : entities.getInterfaces()) {
            ContainerVersion container = draft.getContainerUid() != null
                    ? containersByUid.get(draft.getContainerUid()) : null;
            interfacesByUid.put(draft.getUid(), interfaceMatchService.matchOrCreate(draft.getUid(), draft.getExtUid(),
                    draft.getProtocol(), draft.getName(), null, null, null, null,
                    container != null ? container.getId() : null,
                    draft.getContext(), rawDataRefId, batchId, branch));
        }

        Map<String, OperationVersion> operationsByExtUid = new HashMap<>();
        for (E2ESequenceSnapshot.OperationDraft draft : entities.getOperations()) {
            InterfaceVersion iface = draft.getInterfaceUid() != null
                    ? interfacesByUid.get(draft.getInterfaceUid()) : null;
            operationsByExtUid.put(draft.getExtUid(), operationMatchService.matchOrCreate(
                    draft.getExtUid(), draft.getExtUid(), draft.getName(), draft.getType(),
                    draft.getRps(), draft.getLatency(), draft.getErrorRate(), null, null, null,
                    iface, draft.getContext(), rawDataRefId, batchId, branch,
                    draft.getConnectionOperationId(), draft.getConnectionInterfaceId(), draft.getMatchedOperation()));
        }
        return operationsByExtUid;
    }

    private ArtifactNotice unmappedNotice(long rawDataRefId, String artifactUid, String partId, String reason,
                                          String suggestion) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("part_id", partId);
        details.put("reason", reason);
        details.put("suggestion", suggestion);
        return new ArtifactNotice(null, null, UNMAPPED_SIDE, "warning", "match", rawDataRefId,
                "usecase_step", partId, null, "Шаг не сопоставлен с архитектурной операцией — требуется решение",
                json(details), null, null, artifactUid, null);
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
}
