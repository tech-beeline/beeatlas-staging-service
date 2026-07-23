package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot;
import ru.beeline.staging.product.E2eProductsPublisher;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2ECanonicalSaver implements ArtifactSaver {

    public static final String MODULE_CODE = "e2e-canonical-saver";

    private final E2eCanonicalSnapshotSaver e2eCanonicalSnapshotSaver;
    private final E2eProductsPublisher      e2eProductsPublisher;
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

        E2ESequenceSnapshot snapshot = objectMapper.readValue(canonicalSnapshotJson, E2ESequenceSnapshot.class);

        // Committed on its own (see E2eCanonicalSnapshotSaver) — a publish failure below must not
        // roll back canonical data that was already correctly extracted and saved.
        E2eCanonicalSnapshotSaver.SaveStats stats =
                e2eCanonicalSnapshotSaver.saveSnapshot(snapshot, rawDataRefId, runId, artifactUid, artifactType);
        log.info("Saved canonical model for uid={}: {}", artifactUid, stats);

        e2eProductsPublisher.publish(artifactUid, rawDataRefId);

        return SaveResult.of(Map.of("batchId", stats.getBatchId() != null ? stats.getBatchId() : -1L));
    }
}
