package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.canonical.BiStepVersion;
import ru.beeline.staging.domain.canonical.ContainerVersion;
import ru.beeline.staging.domain.canonical.E2eScenarioVersion;
import ru.beeline.staging.domain.canonical.InterfaceVersion;
import ru.beeline.staging.domain.canonical.OperationRelationVersion;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.domain.canonical.ProductVersion;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.SourceSystemRepository;
import ru.beeline.staging.repository.canonical.OperationRelationVersionRepository;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.RawDataContextService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2ECanonicalSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "e2e-canonical-saver";

    private final OperationRelationVersionRepository operationRelationVersionRepository;
    private final BiStepMatchService                 biStepMatchService;
    private final E2eScenarioMatchService            e2eScenarioMatchService;
    private final ProductMatchService                productMatchService;
    private final ContainerMatchService              containerMatchService;
    private final InterfaceMatchService              interfaceMatchService;
    private final OperationMatchService               operationMatchService;
    private final PipelineRunService                 pipelineRunService;
    private final PipelineRunRepository              pipelineRunRepository;
    private final ConfigurationRepository             configurationRepository;
    private final SourceSystemRepository              sourceSystemRepository;
    private final RawDataContextService               rawDataContextService;
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
                snapshot.getOperations().size(),
                snapshot.getProducts().size(),
                snapshot.getContainers().size());
        Long batchId = batch.getId();

        Map<String, ProductVersion> productVersionsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.ProductDraft draft : snapshot.getProducts()) {
            ProductVersion version = productMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getName(), null, null,
                    draft.getContext(), rawDataRefId, batchId);
            productVersionsByUid.put(draft.getUid(), version);
        }

        Map<String, ContainerVersion> containerVersionsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.ContainerDraft draft : snapshot.getContainers()) {
            ProductVersion productVersion = draft.getProductUid() != null
                    ? productVersionsByUid.get(draft.getProductUid())
                    : null;
            ContainerVersion version = containerMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getName(), null, null, null,
                    productVersion != null ? productVersion.getId() : null,
                    draft.getContext(), rawDataRefId, batchId);
            containerVersionsByUid.put(draft.getUid(), version);
        }

        Map<String, InterfaceVersion> interfaceVersionsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.InterfaceDraft draft : snapshot.getInterfaces()) {
            ContainerVersion containerVersion = draft.getContainerUid() != null
                    ? containerVersionsByUid.get(draft.getContainerUid())
                    : null;
            InterfaceVersion version = interfaceMatchService.matchOrCreate(
                    draft.getUid(), draft.getExtUid(), draft.getProtocol(), draft.getName(),
                    null, null, null, null,
                    containerVersion != null ? containerVersion.getId() : null,
                    draft.getContext(), rawDataRefId, batchId);
            interfaceVersionsByUid.put(draft.getUid(), version);
        }

        Map<String, OperationVersion> operationVersionsByExtUid = new HashMap<>();
        for (E2ESequenceSnapshot.OperationDraft draft : snapshot.getOperations()) {
            InterfaceVersion ifaceVersion = draft.getInterfaceUid() != null
                    ? interfaceVersionsByUid.get(draft.getInterfaceUid())
                    : null;
            OperationVersion version = operationMatchService.matchOrCreate(
                    draft.getExtUid(), draft.getExtUid(), draft.getName(), draft.getType(),
                    draft.getRps(), draft.getLatency(), draft.getErrorRate(),
                    null, null, null,
                    ifaceVersion, draft.getContext(), rawDataRefId, batchId);
            operationVersionsByExtUid.put(draft.getExtUid(), version);
        }

        Map<String, BiStepVersion> biStepVersionsByUid = new HashMap<>();
        for (E2ESequenceSnapshot.BiStepDraft draft : snapshot.getBiSteps()) {
            BiStepVersion version = biStepMatchService.matchOrCreate(
                    draft.getUid(), draft.getName(), draft.getRps(), draft.getLatency(), draft.getErrorRate(),
                    draft.getExtUid(), sourceCode, draft.getContext(), rawDataRefId, batchId);
            biStepVersionsByUid.put(draft.getUid(), version);
        }

        E2ESequenceSnapshot.E2eScenarioDraft scenarioDraft = snapshot.getE2eScenario();
        if (scenarioDraft == null) {
            throw new IllegalStateException("Snapshot for uid=" + artifactUid + " has no e2e_scenario — validator/transformer should have rejected this earlier");
        }
        BiStepVersion linkedBiStep = scenarioDraft.getBiStepUid() != null ? biStepVersionsByUid.get(scenarioDraft.getBiStepUid()) : null;
        E2eScenarioVersion scenarioVersion = e2eScenarioMatchService.matchOrCreate(
                scenarioDraft.getUid(), scenarioDraft.getExtUid(), scenarioDraft.getName(), scenarioDraft.getDescription(),
                linkedBiStep != null ? linkedBiStep.getId() : null,
                scenarioDraft.getContext(), rawDataRefId, batchId);

        int operationRelationsSaved = 0;
        for (E2ESequenceSnapshot.OperationRelationDraft draft : snapshot.getOperationRelations()) {
            OperationVersion callee = operationVersionsByExtUid.get(draft.getCalleeOperationExtUid());
            if (callee == null) {
                log.warn("Skipping operation_relation: missing callee={}", draft.getCalleeOperationExtUid());
                continue;
            }
            // Root-level calls carry no caller operation (operation_version_id stays NULL).
            OperationVersion caller = draft.getCallerOperationExtUid() != null
                    ? operationVersionsByExtUid.get(draft.getCallerOperationExtUid())
                    : null;

            OperationRelationVersion relation = new OperationRelationVersion();
            relation.setOperationVersionId(caller != null ? caller.getId() : null);
            relation.setRelatedOperationVersionId(callee.getId());
            relation.setCallOrder(draft.getCallOrder());
            relation.setStereotype(draft.getStereotype());
            if (draft.getContext() != null) {
                relation.setRawDataContextId(rawDataContextService.pointTo(rawDataRefId, draft.getContext()));
            }
            relation.setCreatedAt(now);
            operationRelationVersionRepository.save(relation);
            operationRelationsSaved++;
        }

        SaveStats stats = new SaveStats();
        stats.setBatchId(batchId);
        stats.setProductsSaved(productVersionsByUid.size());
        stats.setContainersSaved(containerVersionsByUid.size());
        stats.setInterfacesSaved(interfaceVersionsByUid.size());
        stats.setOperationsSaved(operationVersionsByExtUid.size());
        stats.setBiStepsSaved(biStepVersionsByUid.size());
        stats.setE2eScenarioId(scenarioVersion.getE2eScenarioId());
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
        private int  productsSaved;
        private int  containersSaved;
        private int  interfacesSaved;
        private int  operationsSaved;
        private int  biStepsSaved;
        private Long e2eScenarioId;
        private int  operationRelationsSaved;

        @Override
        public String toString() {
            return "batchId=" + batchId + " products=" + productsSaved + " containers=" + containersSaved
                    + " biSteps=" + biStepsSaved + " e2eScenarioId=" + e2eScenarioId
                    + " ifaces=" + interfacesSaved + " ops=" + operationsSaved + " opRelations=" + operationRelationsSaved;
        }
    }
}
