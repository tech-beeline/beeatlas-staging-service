package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.canonical.*;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.SourceSystemRepository;
import ru.beeline.staging.repository.canonical.*;
import ru.beeline.staging.service.PipelineRunService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2ECanonicalSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "e2e-canonical-saver";

    private final InterfaceRepository                  interfaceRepository;
    private final OperationRepository                  operationRepository;
    private final BiStepVersionRepository              biStepVersionRepository;
    private final InterfaceVersionRepository           interfaceVersionRepository;
    private final OperationVersionRepository           operationVersionRepository;
    private final BiStepRelationVersionRepository      biStepRelationVersionRepository;
    private final OperationRelationVersionRepository   operationRelationVersionRepository;
    private final PipelineRunService                   pipelineRunService;
    private final PipelineRunRepository                 pipelineRunRepository;
    private final ConfigurationRepository               configurationRepository;
    private final SourceSystemRepository                sourceSystemRepository;
    private final ObjectMapper                          objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Persists the e2e sequence snapshot into the canonical BI/interface/operation model"; }

    @Override
    @Transactional
    public Map<String, Object> save(String artifactUid, String artifactType, long rawDataRefId,
                                     Long runId, String canonicalSnapshotJson) throws Exception {
        if (canonicalSnapshotJson == null || canonicalSnapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to save", artifactUid);
            return null;
        }

        E2ESequenceSnapshot snapshot = objectMapper.readValue(canonicalSnapshotJson, E2ESequenceSnapshot.class);
        SaveResult result = save(snapshot, rawDataRefId, runId, artifactUid, artifactType);
        log.info("Saved canonical model for uid={}: {}", artifactUid, result);

        return Map.of("batchId", result.getBatchId() != null ? result.getBatchId() : -1L);
    }

    private SaveResult save(E2ESequenceSnapshot snapshot, Long rawDataRefId,
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
            InterfaceEntity entity = interfaceRepository.findByUid(draft.getUid()).orElseGet(() -> {
                InterfaceEntity e = new InterfaceEntity();
                e.setUid(draft.getUid());
                e.setCreatedAt(now);
                return interfaceRepository.save(e);
            });

            InterfaceVersion version = new InterfaceVersion();
            version.setInterfaceId(entity.getId());
            version.setExtUid(draft.getUid());
            version.setProtocol(draft.getProtocol());
            version.setRawDataRefId(rawDataRefId);
            version.setBatchId(batchId);
            version.setCreatedAt(now);
            interfaceVersionsByUid.put(draft.getUid(), interfaceVersionRepository.save(version));
        }

        Map<String, OperationVersion> operationVersionsByExtUid = new HashMap<>();
        for (E2ESequenceSnapshot.OperationDraft draft : snapshot.getOperations()) {
            OperationEntity entity = operationRepository.findByExtUid(draft.getExtUid()).orElseGet(() -> {
                OperationEntity e = new OperationEntity();
                e.setExtUid(draft.getExtUid());
                e.setCreatedAt(now);
                return operationRepository.save(e);
            });

            InterfaceVersion ifaceVersion = draft.getInterfaceUid() != null
                    ? interfaceVersionsByUid.get(draft.getInterfaceUid())
                    : null;
            if (entity.getInterfaceId() == null && ifaceVersion != null) {
                entity.setInterfaceId(ifaceVersion.getInterfaceId());
            }
            entity.setName(draft.getName());
            entity.setType(draft.getType());
            operationRepository.save(entity);

            OperationVersion version = new OperationVersion();
            version.setOperationId(entity.getId());
            version.setInterfaceVersionId(ifaceVersion != null ? ifaceVersion.getId() : null);
            version.setName(draft.getName());
            version.setType(draft.getType());
            version.setRps(toDecimal(draft.getRps()));
            version.setLatency(toDecimal(draft.getLatency()));
            version.setErrorRate(toDecimal(draft.getErrorRate()));
            version.setRawDataRefId(rawDataRefId);
            version.setBatchId(batchId);
            version.setContext(draft.getContext());
            version.setCreatedAt(now);
            operationVersionsByExtUid.put(draft.getExtUid(), operationVersionRepository.save(version));
        }

        Map<String, BiStepVersion> biStepVersionsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.BiStepDraft draft : snapshot.getBiSteps()) {
            // bi_step_id intentionally left null — identity/dedup rule for BiStep not decided yet.
            BiStepVersion version = new BiStepVersion();
            version.setName(draft.getName());
            version.setRps(toDecimal(draft.getRps()));
            version.setLatency(toDecimal(draft.getLatency()));
            version.setErrorRate(toDecimal(draft.getErrorRate()));
            version.setContext(draft.getContext());
            version.setExternalGuid(draft.getExternalGuid());
            version.setSourceId(sourceCode);
            version.setRawDataRefId(rawDataRefId);
            version.setBatchId(batchId);
            version.setCreatedAt(now);
            biStepVersionsByUid.put(draft.getUid(), biStepVersionRepository.save(version));
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
            relation.setContext(draft.getContext());
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
            relation.setContext(draft.getContext());
            relation.setRawDataRefId(rawDataRefId);
            relation.setBatchId(batchId);
            relation.setCreatedAt(now);
            operationRelationVersionRepository.save(relation);
            operationRelationsSaved++;
        }

        SaveResult result = new SaveResult();
        result.setBatchId(batchId);
        result.setInterfacesSaved(interfaceVersionsByUid.size());
        result.setOperationsSaved(operationVersionsByExtUid.size());
        result.setBiStepsSaved(biStepVersionsByUid.size());
        result.setBiStepRelationsSaved(relationsSaved);
        result.setOperationRelationsSaved(operationRelationsSaved);
        return result;
    }

    /** bi_step_versions.source_id — the source system code (staging.source_systems.code) of
     *  this artifact's configuration, e.g. "sparx" — not anything from the raw payload. */
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

    private static BigDecimal toDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : null;
    }

    @Data
    private static class SaveResult {
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
