package ru.beeline.staging.pipeline;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.canonical.*;
import ru.beeline.staging.repository.canonical.*;
import ru.beeline.staging.service.PipelineRunService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists a source-agnostic {@link CanonicalSnapshot} into Beeatlas's own canonical
 * representation (staging.bi_steps / interfaces / operations + their *_versions tables).
 * Entity tables are find-or-create by natural key (uid / ext_uid); version tables are
 * append-only — every load inserts a new version row, traceable back to its raw_data_ref.
 *
 * Each successful save creates one {@link ArtifactBatch} that groups all version rows
 * from this run. The batch is marked is_current=TRUE; the previous batch for the same
 * artifact is marked FALSE. This is how "последний загруженный является эталонным" works.
 *
 * This is "наше представление" — the canonical model lives entirely in staging's own
 * schema; it is not pushed out to any other microservice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanonicalModelSaverService {

    private final BiStepRepository                    biStepRepository;
    private final InterfaceRepository                  interfaceRepository;
    private final OperationRepository                  operationRepository;
    private final BiStepVersionRepository              biStepVersionRepository;
    private final InterfaceVersionRepository           interfaceVersionRepository;
    private final OperationVersionRepository           operationVersionRepository;
    private final BiStepRelationVersionRepository      biStepRelationVersionRepository;
    private final OperationRelationVersionRepository   operationRelationVersionRepository;
    private final PipelineRunService                   pipelineRunService;

    @Transactional
    public SaveResult save(CanonicalSnapshot snapshot, Long rawDataRefId,
                           Long runId, String artifactUid, String artifactType) {
        LocalDateTime now = LocalDateTime.now();

        // Create the batch first — its id is foreign-keyed on all version rows below
        ArtifactBatch batch = pipelineRunService.createBatch(
                artifactUid, artifactType, runId, rawDataRefId,
                snapshot.getBiSteps().size(),
                snapshot.getInterfaces().size(),
                snapshot.getOperations().size());
        Long batchId = batch.getId();

        Map<String, InterfaceVersion> interfaceVersionsByUid = new HashMap<>();
        for (CanonicalSnapshot.InterfaceDraft draft : snapshot.getInterfaces()) {
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
        for (CanonicalSnapshot.OperationDraft draft : snapshot.getOperations()) {
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
        for (CanonicalSnapshot.BiStepDraft draft : snapshot.getBiSteps()) {
            BiStep entity = biStepRepository.findByUid(draft.getUid()).orElseGet(() -> {
                BiStep e = new BiStep();
                e.setUid(draft.getUid());
                e.setCreatedAt(now);
                return biStepRepository.save(e);
            });

            BiStepVersion version = new BiStepVersion();
            version.setBiStepId(entity.getId());
            version.setName(draft.getName());
            version.setRps(toDecimal(draft.getRps()));
            version.setLatency(toDecimal(draft.getLatency()));
            version.setErrorRate(toDecimal(draft.getErrorRate()));
            version.setContext(draft.getContext());
            version.setExternalGuid(draft.getExternalGuid());
            version.setSourceId(draft.getSourceId());
            version.setRawDataRefId(rawDataRefId);
            version.setBatchId(batchId);
            version.setCreatedAt(now);
            biStepVersionsByUid.put(draft.getUid(), biStepVersionRepository.save(version));
        }

        int relationsSaved = 0;
        for (CanonicalSnapshot.BiStepRelationDraft draft : snapshot.getBiStepRelations()) {
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
        for (CanonicalSnapshot.OperationRelationDraft draft : snapshot.getOperationRelations()) {
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

    private static BigDecimal toDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : null;
    }

    @Data
    public static class SaveResult {
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
