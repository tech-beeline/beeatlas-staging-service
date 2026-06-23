package ru.beeline.staging.pipeline.saver;

import java.util.Map;

/**
 * Persists the snapshot produced by a paired ArtifactTransformer into this entity's own
 * canonical model. Selected per configuration by moduleCode. canonicalSnapshotJson is the
 * raw JSON string from raw_data_refs.canonical_snapshot_json — this saver deserializes it
 * itself into whatever private type its paired transformer produced (no shared DTO at the
 * interface level).
 */
public interface ArtifactSaver {

    String moduleCode();

    /** Returns process variables to forward (e.g. batchId), or null/empty if none. */
    Map<String, Object> save(String artifactUid, String artifactType, long rawDataRefId,
                              Long runId, String canonicalSnapshotJson) throws Exception;
}
