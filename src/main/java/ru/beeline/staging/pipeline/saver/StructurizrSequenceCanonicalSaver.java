package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.canonical.ContainerVersion;
import ru.beeline.staging.domain.canonical.InterfaceVersion;
import ru.beeline.staging.domain.canonical.OperationRelationVersion;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.domain.canonical.ProductVersion;
import ru.beeline.staging.domain.canonical.SequenceRelationVersion;
import ru.beeline.staging.domain.canonical.SequenceVersion;
import ru.beeline.staging.domain.canonical.TechCapabilityVersion;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot;
import ru.beeline.staging.repository.canonical.OperationRelationVersionRepository;
import ru.beeline.staging.repository.canonical.SequenceRelationVersionRepository;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.RawDataContextService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists a {@link StructurizrSequenceSnapshot} in the dependency order from
 * structurizr-sequence-transform-rules.md: product -> containers -> tech_capabilities -> interfaces
 * -> operations -> sequences -> sequence_relations -> operation_relations. Each layer is matched via
 * its own MatchService (find-or-create identity + a new *_versions row + provenance notice); later
 * layers resolve their FKs from the ids returned by earlier ones through in-memory uid maps.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructurizrSequenceCanonicalSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "structurizr-sequence-canonical-saver";

    private final ProductMatchService        productMatchService;
    private final ContainerMatchService      containerMatchService;
    private final TechCapabilityMatchService techCapabilityMatchService;
    private final InterfaceMatchService      interfaceMatchService;
    private final OperationMatchService      operationMatchService;
    private final SequenceMatchService       sequenceMatchService;
    private final SequenceRelationVersionRepository  sequenceRelationVersionRepository;
    private final OperationRelationVersionRepository operationRelationVersionRepository;
    private final PipelineRunService         pipelineRunService;
    private final RawDataContextService      rawDataContextService;
    private final ObjectMapper               objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Persists a Structurizr workspace snapshot (product/container/tc/interface/operation/sequence) into the canonical model"; }

    @Override
    @Transactional
    public SaveResult save(String artifactUid, String artifactType, long rawDataRefId,
                            Long runId, String canonicalSnapshotJson) throws Exception {
        if (canonicalSnapshotJson == null || canonicalSnapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to save", artifactUid);
            return SaveResult.of(Map.of());
        }

        StructurizrSequenceSnapshot snapshot = objectMapper.readValue(canonicalSnapshotJson, StructurizrSequenceSnapshot.class);
        if (snapshot.getProduct() == null) {
            throw new IllegalStateException("Snapshot for uid=" + artifactUid + " has no product — validator/transformer should have rejected this earlier");
        }

        ArtifactBatch batch = pipelineRunService.createBatch(
                artifactUid, artifactType, runId, rawDataRefId,
                0, snapshot.getInterfaces().size(), snapshot.getOperations().size(),
                1, snapshot.getContainers().size());
        Long batchId = batch.getId();

        StructurizrSequenceSnapshot.ProductDraft productDraft = snapshot.getProduct();
        ProductVersion productVersion = productMatchService.matchOrCreate(
                productDraft.getUid(), productDraft.getExtUid(), productDraft.getName(), productDraft.getDescription(),
                productDraft.getAuthor(), productDraft.getContext(), rawDataRefId, batchId);

        Map<String, ContainerVersion> containerVersionsByUid = new HashMap<>();
        for (StructurizrSequenceSnapshot.ContainerDraft draft : snapshot.getContainers()) {
            ContainerVersion version = containerMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getName(), draft.getVersion(), draft.getDescription(),
                    draft.getTechnology(), productVersion.getId(), draft.getContext(), rawDataRefId, batchId);
            containerVersionsByUid.put(draft.getUid(), version);
        }

        Map<String, TechCapabilityVersion> tcVersionsByUid = new HashMap<>();
        for (StructurizrSequenceSnapshot.TechCapabilityDraft draft : snapshot.getTechCapabilities()) {
            TechCapabilityVersion version = techCapabilityMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getName(), draft.getDescription(),
                    draft.getContext(), rawDataRefId, batchId);
            tcVersionsByUid.put(draft.getUid(), version);
        }

        Map<String, InterfaceVersion> interfaceVersionsByUid = new HashMap<>();
        for (StructurizrSequenceSnapshot.InterfaceDraft draft : snapshot.getInterfaces()) {
            ContainerVersion containerVersion = draft.getContainerUid() != null
                    ? containerVersionsByUid.get(draft.getContainerUid())
                    : null;
            InterfaceVersion version = interfaceMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getProtocol(), draft.getName(),
                    draft.getSpecLink(), draft.getVersion(), draft.getDescription(), null,
                    containerVersion != null ? containerVersion.getId() : null,
                    draft.getContext(), rawDataRefId, batchId);
            interfaceVersionsByUid.put(draft.getUid(), version);
        }

        Map<String, OperationVersion> operationVersionsByUid = new HashMap<>();
        for (StructurizrSequenceSnapshot.OperationDraft draft : snapshot.getOperations()) {
            InterfaceVersion ifaceVersion = draft.getInterfaceUid() != null
                    ? interfaceVersionsByUid.get(draft.getInterfaceUid())
                    : null;
            TechCapabilityVersion tcVersion = draft.getTechCapabilityUid() != null
                    ? tcVersionsByUid.get(draft.getTechCapabilityUid())
                    : null;
            OperationVersion version = operationMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getName(), draft.getType(),
                    draft.getRps(), draft.getLatency(), draft.getErrorRate(),
                    null, null, tcVersion != null ? tcVersion.getId() : null,
                    ifaceVersion, draft.getContext(), rawDataRefId, batchId);
            operationVersionsByUid.put(draft.getUid(), version);
        }

        Map<String, SequenceVersion> sequenceVersionsByUid = new HashMap<>();
        for (StructurizrSequenceSnapshot.SequenceDraft draft : snapshot.getSequences()) {
            TechCapabilityVersion tcVersion = draft.getTechCapabilityUid() != null
                    ? tcVersionsByUid.get(draft.getTechCapabilityUid())
                    : null;
            SequenceVersion version = sequenceMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getName(), draft.getDescription(),
                    tcVersion != null ? tcVersion.getId() : null,
                    draft.getContext(), rawDataRefId, batchId);
            sequenceVersionsByUid.put(draft.getUid(), version);
        }

        LocalDateTime now = LocalDateTime.now();
        int sequenceRelationsSaved = 0;
        for (StructurizrSequenceSnapshot.SequenceRelationDraft draft : snapshot.getSequenceRelations()) {
            SequenceVersion sequenceVersion = sequenceVersionsByUid.get(draft.getSequenceUid());
            if (sequenceVersion == null) {
                log.warn("Skipping sequence_relation: missing sequence={}", draft.getSequenceUid());
                continue;
            }
            OperationVersion operation = draft.getOperationUid() != null ? operationVersionsByUid.get(draft.getOperationUid()) : null;

            SequenceRelationVersion relation = new SequenceRelationVersion();
            relation.setSequenceVersionId(sequenceVersion.getId());
            relation.setOperationVersionId(operation != null ? operation.getId() : null);
            relation.setCallOrder(draft.getCallOrder());
            relation.setStereotype(draft.getStereotype());
            if (draft.getContext() != null) {
                relation.setRawDataContextId(rawDataContextService.pointTo(rawDataRefId, draft.getContext()));
            }
            relation.setCreatedAt(now);
            sequenceRelationVersionRepository.save(relation);
            sequenceRelationsSaved++;
        }

        int operationRelationsSaved = 0;
        for (StructurizrSequenceSnapshot.OperationRelationDraft draft : snapshot.getOperationRelations()) {
            OperationVersion related = draft.getRelatedOperationUid() != null ? operationVersionsByUid.get(draft.getRelatedOperationUid()) : null;
            if (related == null) {
                log.warn("Skipping operation_relation: missing related operation={}", draft.getRelatedOperationUid());
                continue;
            }
            OperationVersion caller = draft.getOperationUid() != null ? operationVersionsByUid.get(draft.getOperationUid()) : null;

            OperationRelationVersion relation = new OperationRelationVersion();
            relation.setOperationVersionId(caller != null ? caller.getId() : null);
            relation.setRelatedOperationVersionId(related.getId());
            relation.setCallOrder(draft.getCallOrder());
            relation.setStereotype(draft.getStereotype());
            if (draft.getContext() != null) {
                relation.setRawDataContextId(rawDataContextService.pointTo(rawDataRefId, draft.getContext()));
            }
            relation.setCreatedAt(now);
            operationRelationVersionRepository.save(relation);
            operationRelationsSaved++;
        }

        log.info("Saved canonical model for uid={}: productId={} containers={} techCapabilities={} interfaces={} operations={} " +
                        "sequences={} sequenceRelations={} operationRelations={}",
                artifactUid, productVersion.getProductId(), containerVersionsByUid.size(), tcVersionsByUid.size(),
                interfaceVersionsByUid.size(), operationVersionsByUid.size(), sequenceVersionsByUid.size(),
                sequenceRelationsSaved, operationRelationsSaved);

        return SaveResult.of(Map.of(
                "batchId", batchId,
                "productId", productVersion.getProductId(),
                "containersSaved", containerVersionsByUid.size(),
                "techCapabilitiesSaved", tcVersionsByUid.size(),
                "interfacesSaved", interfaceVersionsByUid.size(),
                "operationsSaved", operationVersionsByUid.size(),
                "sequencesSaved", sequenceVersionsByUid.size(),
                "sequenceRelationsSaved", sequenceRelationsSaved,
                "operationRelationsSaved", operationRelationsSaved));
    }
}
