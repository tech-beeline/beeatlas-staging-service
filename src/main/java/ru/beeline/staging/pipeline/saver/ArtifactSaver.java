package ru.beeline.staging.pipeline.saver;

import java.util.Map;

public interface ArtifactSaver {

    String moduleCode();

    String description();

    Map<String, Object> save(String artifactUid, String artifactType, long rawDataRefId,
                              Long runId, String canonicalSnapshotJson) throws Exception;
}
