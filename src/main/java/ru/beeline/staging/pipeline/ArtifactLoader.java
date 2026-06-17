package ru.beeline.staging.pipeline;

import java.util.Map;

/**
 * Strategy for downloading raw artifact data from its source system and storing it
 * (e.g. into S3 + raw_data_refs). One implementation per artifactType; LoaderWorker
 * dispatches to the matching bean by {@link #supportedType()}.
 */
public interface ArtifactLoader {

    String supportedType();

    /**
     * Loads the artifact identified by artifactUid, persists the raw snapshot and
     * returns process variables to forward to the next pipeline stage (must include
     * "rawDataRefId").
     */
    Map<String, Object> load(String artifactUid, String sourceId, Map<String, Object> metadata) throws Exception;
}
