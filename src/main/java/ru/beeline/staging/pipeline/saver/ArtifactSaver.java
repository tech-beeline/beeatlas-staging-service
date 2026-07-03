package ru.beeline.staging.pipeline.saver;

import ru.beeline.staging.dto.notice.SaveResult;

public interface ArtifactSaver {

    String moduleCode();

    String description();

    SaveResult save(String artifactUid, String artifactType, long rawDataRefId,
                    Long runId, String canonicalSnapshotJson) throws Exception;
}
