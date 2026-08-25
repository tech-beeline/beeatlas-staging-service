package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dashboard.DashboardServicePublishClient;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.MetricQueriesObjectPublish;
import ru.beeline.staging.service.PipelineRunService;

import java.util.Map;
import java.util.Optional;

/**
 * Ported from documentation/staging-service/source-artefacts/metric-queries/metric-queries-save-spec.md
 * — keep in sync with that spec.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueriesSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "metric-queries-saver";

    private final MetricQueryTemplateSnapshotSaver snapshotSaver;
    private final DashboardServicePublishClient    publishClient;
    private final PipelineRunService               pipelineRunService;
    private final ObjectMapper                     objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Persists the metric-queries canonical snapshot and publishes it to dashboard-service"; }

    @Override
    public SaveResult save(String artifactUid, String artifactType, long rawDataRefId,
                            Long runId, String canonicalSnapshotJson) throws Exception {
        if (canonicalSnapshotJson == null || canonicalSnapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to save", artifactUid);
            return SaveResult.of(Map.of());
        }

        MetricQueriesObjectPublish snapshot = objectMapper.readValue(canonicalSnapshotJson, MetricQueriesObjectPublish.class);

        // Same idempotency guard as E2ECanonicalSaver: if a previous attempt already committed the
        // canonical save and only failed later at publish, don't insert a second version — retry
        // only the publish.
        Optional<ArtifactBatch> existingBatch = pipelineRunService.findExistingBatchForRef(artifactUid, artifactType, rawDataRefId);
        Long batchId;
        if (existingBatch.isPresent()) {
            batchId = existingBatch.get().getId();
            log.info("metric-queries snapshot already saved for uid={} rawDataRefId={} (batchId={}) — retrying publish only",
                    artifactUid, rawDataRefId, batchId);
        } else {
            batchId = snapshotSaver.saveSnapshot(snapshot, rawDataRefId, runId, artifactUid, artifactType);
            log.info("Saved metric-queries snapshot for uid={}: batchId={}", artifactUid, batchId);
        }

        boolean published = publishClient.publish(snapshot, rawDataRefId);

        return SaveResult.of(Map.of("batchId", batchId != null ? batchId : -1L, "published", published));
    }
}
