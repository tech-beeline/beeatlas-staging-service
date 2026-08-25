/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot;
import ru.beeline.staging.product.E2eProductsPublisher;
import ru.beeline.staging.service.PipelineRunService;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2ECanonicalSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "e2e-canonical-saver";

    private final E2eCanonicalSnapshotSaver e2eCanonicalSnapshotSaver;
    private final E2eProductsPublisher      e2eProductsPublisher;
    private final PipelineRunService        pipelineRunService;
    private final ObjectMapper              objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Persists the e2e sequence snapshot into the canonical BI/interface/operation model"; }

    @Override
    public SaveResult save(String artifactUid, String artifactType, long rawDataRefId,
                           Long runId, String canonicalSnapshotJson) throws Exception {
        if (canonicalSnapshotJson == null || canonicalSnapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to save", artifactUid);
            return SaveResult.of(Map.of());
        }
        Optional<ArtifactBatch> existingBatch = pipelineRunService.findExistingBatchForRef(artifactUid, artifactType, rawDataRefId);
        Long batchId;
        if (existingBatch.isPresent()) {
            batchId = existingBatch.get().getId();
            log.info("Canonical model already saved for uid={} rawDataRefId={} (batchId={}) — retrying publish only",
                    artifactUid, rawDataRefId, batchId);
        } else {
            E2ESequenceSnapshot snapshot = objectMapper.readValue(canonicalSnapshotJson, E2ESequenceSnapshot.class);
            E2eCanonicalSnapshotSaver.SaveStats stats =
                    e2eCanonicalSnapshotSaver.saveSnapshot(snapshot, rawDataRefId, runId, artifactUid, artifactType);
            log.info("Saved canonical model for uid={}: {}", artifactUid, stats);
            batchId = stats.getBatchId();
        }

        e2eProductsPublisher.publish(artifactUid, rawDataRefId, runId);

        return SaveResult.of(Map.of("batchId", batchId != null ? batchId : -1L));
    }
}
