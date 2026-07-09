package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.canonical.InterfaceVersion;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.domain.canonical.SequenceRelationVersion;
import ru.beeline.staging.domain.canonical.SequenceVersion;
import ru.beeline.staging.domain.canonical.TcVersion;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot;
import ru.beeline.staging.repository.canonical.SequenceRelationVersionRepository;
import ru.beeline.staging.service.PipelineRunService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StructurizrSequenceCanonicalSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "structurizr-sequence-canonical-saver";

    private final TcMatchService        tcMatchService;
    private final SequenceMatchService  sequenceMatchService;
    private final InterfaceMatchService interfaceMatchService;
    private final OperationMatchService operationMatchService;
    private final SequenceRelationVersionRepository sequenceRelationVersionRepository;
    private final PipelineRunService    pipelineRunService;
    private final ObjectMapper          objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Persists the Structurizr dynamic-view snapshot into the canonical Tc/Sequence model"; }

    @Override
    @Transactional
    public SaveResult save(String artifactUid, String artifactType, long rawDataRefId,
                            Long runId, String canonicalSnapshotJson) throws Exception {
        if (canonicalSnapshotJson == null || canonicalSnapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to save", artifactUid);
            return SaveResult.of(Map.of());
        }

        StructurizrSequenceSnapshot snapshot = objectMapper.readValue(canonicalSnapshotJson, StructurizrSequenceSnapshot.class);

        ArtifactBatch batch = pipelineRunService.createBatch(
                artifactUid, artifactType, runId, rawDataRefId,
                snapshot.getSequences().size(),
                snapshot.getInterfaces().size(),
                snapshot.getOperations().size());
        Long batchId = batch.getId();

        StructurizrSequenceSnapshot.TcDraft tcDraft = snapshot.getTc();
        TcVersion tcVersion = tcMatchService.matchOrCreate(tcDraft.getTcCode(), tcDraft.getName(), tcDraft.getDescription(),
                tcDraft.getProductId(), tcDraft.getContext(), rawDataRefId, batchId);

        Map<String, InterfaceVersion> interfaceVersionsByUid = new HashMap<>();
        for (StructurizrSequenceSnapshot.InterfaceDraft draft : snapshot.getInterfaces()) {
            InterfaceVersion version = interfaceMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getProtocol(), draft.getSource(),
                    draft.getContext(), rawDataRefId, batchId);
            interfaceVersionsByUid.put(draft.getUid(), version);
        }

        Map<String, OperationVersion> operationVersionsByExtUid = new HashMap<>();
        for (StructurizrSequenceSnapshot.OperationDraft draft : snapshot.getOperations()) {
            InterfaceVersion ifaceVersion = draft.getInterfaceUid() != null
                    ? interfaceVersionsByUid.get(draft.getInterfaceUid())
                    : null;
            OperationVersion version = operationMatchService.matchOrCreate(
                    draft.getExtUid(), draft.getName(), null,
                    null, null, null,
                    ifaceVersion, draft.getContext(), rawDataRefId, batchId);
            operationVersionsByExtUid.put(draft.getExtUid(), version);
        }

        Map<String, SequenceVersion> sequenceVersionsByKey = new HashMap<>();
        for (StructurizrSequenceSnapshot.SequenceDraft draft : snapshot.getSequences()) {
            SequenceVersion version = sequenceMatchService.matchOrCreate(
                    tcVersion.getTcId(), draft.getTcCode(), draft.getKey(), draft.getName(), draft.getDescription(),
                    draft.getContext(), rawDataRefId, batchId);
            sequenceVersionsByKey.put(draft.getKey(), version);
        }

        LocalDateTime now = LocalDateTime.now();
        int relationsSaved = 0;
        for (StructurizrSequenceSnapshot.SequenceRelationDraft draft : snapshot.getSequenceRelations()) {
            SequenceVersion sequenceVersion = sequenceVersionsByKey.get(draft.getSequenceKey());
            OperationVersion caller = operationVersionsByExtUid.get(draft.getCallerOperationExtUid());
            OperationVersion callee = operationVersionsByExtUid.get(draft.getCalleeOperationExtUid());
            if (sequenceVersion == null || callee == null) {
                log.warn("Skipping sequence_relation: missing sequence={} or callee operation={}",
                        draft.getSequenceKey(), draft.getCalleeOperationExtUid());
                continue;
            }
            SequenceRelationVersion relation = new SequenceRelationVersion();
            relation.setSequenceVersionId(sequenceVersion.getId());
            relation.setCallerOperationVersionId(caller != null ? caller.getId() : null);
            relation.setCalleeOperationVersionId(callee.getId());
            relation.setCallOrder(draft.getCallOrder());
            relation.setStereotype(draft.getStereotype());
            relation.setRawDataRefId(rawDataRefId);
            relation.setBatchId(batchId);
            relation.setContext(draft.getContext());
            relation.setCreatedAt(now);
            sequenceRelationVersionRepository.save(relation);
            relationsSaved++;
        }

        log.info("Saved canonical Tc/Sequence model for uid={}: tcId={} sequences={} interfaces={} operations={} sequenceRelations={}",
                artifactUid, tcVersion.getTcId(), sequenceVersionsByKey.size(), interfaceVersionsByUid.size(),
                operationVersionsByExtUid.size(), relationsSaved);

        return SaveResult.of(Map.of(
                "batchId", batchId,
                "tcId", tcVersion.getTcId(),
                "sequencesSaved", sequenceVersionsByKey.size(),
                "operationsSaved", operationVersionsByExtUid.size(),
                "sequenceRelationsSaved", relationsSaved));
    }
}
