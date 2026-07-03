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
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.SourceSystemRepository;
import ru.beeline.staging.repository.canonical.*;
import ru.beeline.staging.service.ArtifactNoticeService;
import ru.beeline.staging.service.PipelineRunService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
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
    private final PipelineRunRepository                pipelineRunRepository;
    private final ConfigurationRepository              configurationRepository;
    private final SourceSystemRepository               sourceSystemRepository;
    private final ArtifactNoticeService                noticeService;
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
        String artifactContext = toJson(Map.of("stage", "saver", "artifact_uid", artifactUid));

        ArtifactBatch batch = pipelineRunService.createBatch(
                artifactUid, artifactType, runId, rawDataRefId,
                snapshot.getBiSteps().size(),
                snapshot.getInterfaces().size(),
                snapshot.getOperations().size());
        Long batchId = batch.getId();

        Map<String, InterfaceVersion> interfaceVersionsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.InterfaceDraft draft : snapshot.getInterfaces()) {
            boolean[] created = {false};
            InterfaceEntity entity = interfaceRepository.findByUid(draft.getUid()).orElseGet(() -> {
                created[0] = true;
                InterfaceEntity e = new InterfaceEntity();
                e.setUid(draft.getUid());
                e.setCreatedAt(now);
                return interfaceRepository.save(e);
            });

            String noticeCode = created[0] ? "match.interface.created" : "match.interface.matched_by_uid";
            Long matchNoticeId = saveMatchNotice(noticeCode, rawDataRefId, "interface", draft.getUid(), null, artifactContext);

            InterfaceVersion version = new InterfaceVersion();
            version.setInterfaceId(entity.getId());
            version.setExtUid(draft.getUid());
            version.setProtocol(draft.getProtocol());
            version.setRawDataRefId(rawDataRefId);
            version.setBatchId(batchId);
            version.setCreatedAt(now);
            version.setMatchNoticeId(matchNoticeId);
            interfaceVersionsByUid.put(draft.getUid(), interfaceVersionRepository.save(version));
        }

        Map<String, OperationVersion> operationVersionsByExtUid = new HashMap<>();
        for (E2ESequenceSnapshot.OperationDraft draft : snapshot.getOperations()) {
            boolean[] created = {false};
            OperationEntity entity = operationRepository.findByExtUid(draft.getExtUid()).orElseGet(() -> {
                created[0] = true;
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

            String noticeCode = created[0] ? "match.operation.created" : "match.operation.matched_by_ext_uid";
            Long matchNoticeId = saveMatchNotice(noticeCode, rawDataRefId, "operation", draft.getExtUid(), null, artifactContext);

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
            version.setMatchNoticeId(matchNoticeId);
            operationVersionsByExtUid.put(draft.getExtUid(), operationVersionRepository.save(version));
        }

        Map<String, BiStepVersion> biStepVersionsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.BiStepDraft draft : snapshot.getBiSteps()) {
            // bi_step_id intentionally left null — identity/dedup rule for BiStep not decided yet.
            Long matchNoticeId = saveMatchNotice("match.bi_step.always_new", rawDataRefId, "bi_step", draft.getUid(), null, artifactContext);

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
            version.setMatchNoticeId(matchNoticeId);
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

        SaveStats stats = new SaveStats();
        stats.setBatchId(batchId);
        stats.setInterfacesSaved(interfaceVersionsByUid.size());
        stats.setOperationsSaved(operationVersionsByExtUid.size());
        stats.setBiStepsSaved(biStepVersionsByUid.size());
        stats.setBiStepRelationsSaved(relationsSaved);
        stats.setOperationRelationsSaved(operationRelationsSaved);
        return stats;
    }

    private Long saveMatchNotice(String code, Long rawDataRefId, String entityType, String entityUid,
                                 Long entityVersionId, String context) {
        ArtifactNotice notice = new ArtifactNotice(
                null, null, code, "info", "match",
                rawDataRefId, entityType, entityUid, entityVersionId,
                code, null, context
        );
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0).id();
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

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static BigDecimal toDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : null;
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
