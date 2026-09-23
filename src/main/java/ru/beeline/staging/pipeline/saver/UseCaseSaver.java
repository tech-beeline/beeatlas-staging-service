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
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.dto.usecase.UseCaseDraft;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.StepVersionRow;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeOperation;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.RunBranchResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UseCaseSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "usecase-saver";
    public static final String UNMAPPED_SIDE = "match.usecase_step.unmapped_side";

    private static final String SUGGESTION = "map_existing | create_new";

    private final UseCaseCanonicalRepository canonicalRepository;
    private final UseCaseLandscapeRepository landscapeRepository;
    private final PipelineRunService         pipelineRunService;
    private final RunBranchResolver          runBranchResolver;
    private final ObjectMapper               objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Writes the UseCase and its steps to the canonical model (phase 1)"; }

    @Override
    @Transactional
    public SaveResult save(String artifactUid, String artifactType, long rawDataRefId,
                           Long runId, String canonicalSnapshotJson) throws Exception {
        if (canonicalSnapshotJson == null || canonicalSnapshotJson.isBlank()) {
            throw new IllegalStateException("canonical_snapshot_json is empty for usecase run " + runId);
        }
        UseCaseDraft snapshot = objectMapper.readValue(canonicalSnapshotJson, UseCaseDraft.class);
        String branch = runBranchResolver.resolve(runId);
        UseCaseDraft.Header header = snapshot.usecase() != null
                ? snapshot.usecase() : new UseCaseDraft.Header(artifactUid, null, null, null);

        Long usecaseId = canonicalRepository.findOrCreateUseCase(artifactUid, header.projectCode());
        Long biStepVersionId = header.biStepCode() == null ? null
                : landscapeRepository.findBiStepVersionId(header.biStepCode(), branch).orElse(null);
        ArtifactBatch batch = pipelineRunService.createBatch(artifactUid, artifactType, runId, rawDataRefId, 0, 0, 0);

        Map<String, Object> usecaseAttributes = new LinkedHashMap<>();
        usecaseAttributes.put("code", header.code());
        usecaseAttributes.put("bi_step_code", header.biStepCode());
        Long usecaseVersionId = canonicalRepository.insertUseCaseVersion(usecaseId, biStepVersionId, runId,
                artifactUid, header.name(), header.projectCode(), branch, json(usecaseAttributes));

        List<ArtifactNotice> notices = new ArrayList<>();
        int stepsSaved = 0;
        int unmapped = 0;

        for (UseCaseDraft.MappedPart part : snapshot.mappedOrEmpty()) {
            LandscapeOperation callee = operationOf(part.operation(), part.interfaceCode(), branch);
            UseCaseDraft.Side callerSide = part.caller();
            LandscapeOperation caller = callerSide == null ? null
                    : operationOf(callerSide.operation(), callerSide.interfaceCode(), branch);

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("operation_code", part.operation());
            attributes.put("interface_code", part.interfaceCode());
            attributes.put("container_code", part.container());
            attributes.put("tc_code", part.tcCode());
            attributes.put("sequence_code", part.sequenceCode());
            attributes.put("dynamic_diagram_url", part.dynamicDiagramUrl());
            attributes.put("bi_step_code", header.biStepCode());
            if (callee == null) {
                unmapped++;
                attributes.put("reason", "Операция " + part.operation() + " не найдена в ландшафте ветки " + branch);
                attributes.put("suggestion", SUGGESTION);
                notices.add(unmappedNotice(rawDataRefId, artifactUid, part.partId(),
                        (String) attributes.get("reason")));
            }

            canonicalRepository.insertStepVersion(new StepVersionRow(usecaseVersionId,
                    caller == null ? null : caller.operationVersionId(),
                    callee == null ? null : callee.operationVersionId(),
                    part.partId(), part.name(), part.seq(), part.scenarioType(),
                    callee == null ? null : UseCaseDraft.CALL_STATUS_CONFIRMED,
                    part.stepType(), branch, json(attributes)));
            stepsSaved++;
        }

        for (UseCaseDraft.UnmappedPart part : snapshot.unmappedOrEmpty()) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("reason", part.reason());
            attributes.put("participants", part.participants());
            attributes.put("suggestion", part.suggestion() == null ? SUGGESTION : part.suggestion());
            attributes.put("bi_step_code", header.biStepCode());

            canonicalRepository.insertStepVersion(new StepVersionRow(usecaseVersionId, null, null,
                    part.partId(), part.name(), part.seq(), part.scenarioType(), null, part.stepType(),
                    branch, json(attributes)));
            notices.add(unmappedNotice(rawDataRefId, artifactUid, part.partId(), part.reason()));
            stepsSaved++;
            unmapped++;
        }

        pipelineRunService.saveNotices(rawDataRefId, notices);

        log.info("Saved usecase uid={} run={} branch={}: usecaseVersionId={} steps={} unmapped={}",
                artifactUid, runId, branch, usecaseVersionId, stepsSaved, unmapped);
        return SaveResult.of(Map.of(
                "batchId", batch.getId(),
                "usecaseId", usecaseId,
                "usecaseVersionId", usecaseVersionId,
                "stepsSaved", stepsSaved,
                "unmapped", unmapped));
    }

    private LandscapeOperation operationOf(String operationCode, String interfaceCode, String branch) {
        return operationCode == null ? null
                : landscapeRepository.findOperationByCode(operationCode, interfaceCode, branch).orElse(null);
    }

    private ArtifactNotice unmappedNotice(long rawDataRefId, String artifactUid, String partId, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("part_id", partId);
        details.put("reason", reason);
        details.put("suggestion", SUGGESTION);
        return new ArtifactNotice(null, null, UNMAPPED_SIDE, "warning", "match", rawDataRefId,
                "usecase_step", partId, null, "Сторона вызова не смаппирована — требуется решение",
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
