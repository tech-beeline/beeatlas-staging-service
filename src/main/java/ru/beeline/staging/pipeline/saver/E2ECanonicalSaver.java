package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.canonical.BiStepRelationVersion;
import ru.beeline.staging.domain.canonical.BiStepVersion;
import ru.beeline.staging.domain.canonical.InterfaceVersion;
import ru.beeline.staging.domain.canonical.OperationRelationVersion;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.SourceSystemRepository;
import ru.beeline.staging.repository.canonical.BiStepRelationVersionRepository;
import ru.beeline.staging.repository.canonical.OperationRelationVersionRepository;
import ru.beeline.staging.service.PipelineRunService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2ECanonicalSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "e2e-canonical-saver";

    private final BiStepRelationVersionRepository    biStepRelationVersionRepository;
    private final OperationRelationVersionRepository operationRelationVersionRepository;
    private final BiStepMatchService                 biStepMatchService;
    private final InterfaceMatchService              interfaceMatchService;
    private final OperationMatchService               operationMatchService;
    private final PipelineRunService                 pipelineRunService;
    private final PipelineRunRepository              pipelineRunRepository;
    private final ConfigurationRepository             configurationRepository;
    private final SourceSystemRepository              sourceSystemRepository;
    private final ObjectMapper                         objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Persists the e2e sequence snapshot into the canonical BI/interface/operation model"; }

    @Override
    @Transactional
    public SaveResult save(String artifactUid, String artifactType, long rawDataRefId,
                           Long runId, String canonicalSnapshotJson) throws Exception {
        if (canonicalSnapshotJson == null || canonicalSnapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to save", artifactUid);
            return SaveResult.of(Map.of());
        }

        E2ESequenceSnapshot snapshot = objectMapper.readValue(canonicalSnapshotJson, E2ESequenceSnapshot.class);
        SaveStats stats = saveSnapshot(snapshot, rawDataRefId, runId, artifactUid, artifactType);
        log.info("Saved canonical model for uid={}: {}", artifactUid, stats);

        return SaveResult.of(Map.of("batchId", stats.getBatchId() != null ? stats.getBatchId() : -1L));
    }

    private SaveStats saveSnapshot(E2ESequenceSnapshot snapshot, Long rawDataRefId,
                                   Long runId, String artifactUid, String artifactType) {
        LocalDateTime now = LocalDateTime.now();
        String sourceCode = resolveSourceCode(runId);

        ArtifactBatch batch = pipelineRunService.createBatch(
                artifactUid, artifactType, runId, rawDataRefId,
                snapshot.getBiSteps().size(),
                snapshot.getInterfaces().size(),
                snapshot.getOperations().size());
        Long batchId = batch.getId();

        Map<String, InterfaceVersion> interfaceVersionsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.InterfaceDraft draft : snapshot.getInterfaces()) {
            InterfaceVersion version = interfaceMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getProtocol(), draft.getSource(),
                    draft.getContext(), rawDataRefId, batchId);
            interfaceVersionsByUid.put(draft.getUid(), version);
        }

        Map<String, OperationVersion> operationVersionsByExtUid = new HashMap<>();
        for (E2ESequenceSnapshot.OperationDraft draft : snapshot.getOperations()) {
            InterfaceVersion ifaceVersion = draft.getInterfaceUid() != null
                    ? interfaceVersionsByUid.get(draft.getInterfaceUid())
                    : null;
            OperationVersion version = operationMatchService.matchOrCreate(
                    draft.getExtUid(), draft.getName(), draft.getType(),
                    draft.getRps(), draft.getLatency(), draft.getErrorRate(),
                    ifaceVersion, draft.getContext(), rawDataRefId, batchId);
            operationVersionsByExtUid.put(draft.getExtUid(), version);
        }

        Map<String, BiStepVersion> biStepVersionsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.BiStepDraft draft : snapshot.getBiSteps()) {
            BiStepVersion version = biStepMatchService.matchOrCreate(
                    draft.getUid(), draft.getName(), draft.getRps(), draft.getLatency(), draft.getErrorRate(),
                    draft.getExternalGuid(), sourceCode, draft.getContext(), rawDataRefId, batchId);
            biStepVersionsByUid.put(draft.getUid(), version);
        }

        int relationsSaved = 0;
        for (E2ESequenceSnapshot.BiStepRelationDraft draft : snapshot.getBiStepRelations()) {
            BiStepVersion step = biStepVersionsByUid.get(draft.getBiStepUid());
            OperationVersion op = operationVersionsByExtUid.get(draft.getOperationExtUid());
            if (step == null || op == null) {
                log.warn("Skipping bi_step_relation: missing step={} or operation={}", draft.getBiStepUid(), draft.getOperationExtUid());
                continue;
            }
            BiStepRelationVersion relation = new BiStepRelationVersion();
            relation.setBiStepVersionId(step.getId());
            relation.setOperationVersionId(op.getId());
            relation.setCallOrder(draft.getCallOrder());
            relation.setStereotype(draft.getStereotype());
            relation.setRawDataRefId(rawDataRefId);
            relation.setBatchId(batchId);
            relation.setCreatedAt(now);
            biStepRelationVersionRepository.save(relation);
            relationsSaved++;
        }

        int operationRelationsSaved = 0;
        for (E2ESequenceSnapshot.OperationRelationDraft draft : snapshot.getOperationRelations()) {
            OperationVersion caller = operationVersionsByExtUid.get(draft.getCallerOperationExtUid());
            OperationVersion callee = operationVersionsByExtUid.get(draft.getCalleeOperationExtUid());
            if (caller == null || callee == null) {
                log.warn("Skipping operation_relation: missing caller={} or callee={}", draft.getCallerOperationExtUid(), draft.getCalleeOperationExtUid());
                continue;
            }
            OperationRelationVersion relation = new OperationRelationVersion();
            relation.setOperationVersionId(caller.getId());
            relation.setCalleeOperationVersionId(callee.getId());
            relation.setCallOrder(draft.getCallOrder());
            relation.setStereotype(draft.getStereotype());
            relation.setRawDataRefId(rawDataRefId);
            relation.setBatchId(batchId);
            relation.setCreatedAt(now);
            operationRelationVersionRepository.save(relation);
            operationRelationsSaved++;
        }

        SaveStats stats = new SaveStats();
        stats.setBatchId(batchId);
        stats.setInterfacesSaved(interfaceVersionsByUid.size());
        stats.setOperationsSaved(operationVersionsByExtUid.size());
        stats.setBiStepsSaved(biStepVersionsByUid.size());
        stats.setBiStepRelationsSaved(relationsSaved);
        stats.setOperationRelationsSaved(operationRelationsSaved);
        return stats;
    }

    private String resolveSourceCode(Long runId) {
        if (runId == null) return null;
        return pipelineRunRepository.findById(runId)
                .map(PipelineRun::getConfigurationId)
                .flatMap(configurationRepository::findById)
                .map(ru.beeline.staging.domain.Configuration::getSourceSystemId)
                .flatMap(sourceSystemId -> sourceSystemRepository.findById(sourceSystemId.intValue()))
                .map(ru.beeline.staging.domain.SourceSystem::getCode)
                .orElse(null);
    }

    @Data
    private static class SaveStats {
        private Long batchId;
        private int  interfacesSaved;
        private int  operationsSaved;
        private int  biStepsSaved;
        private int  biStepRelationsSaved;
        private int  operationRelationsSaved;

        @Override
        public String toString() {
            return "batchId=" + batchId + " biSteps=" + biStepsSaved
                    + " ifaces=" + interfacesSaved + " ops=" + operationsSaved
                    + " biRelations=" + biStepRelationsSaved + " opRelations=" + operationRelationsSaved;
        }
    }
}
