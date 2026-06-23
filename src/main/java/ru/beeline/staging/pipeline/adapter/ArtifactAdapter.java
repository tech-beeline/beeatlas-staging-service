package ru.beeline.staging.pipeline.adapter;

import java.util.Map;

/**
 * Downloads raw ("грязные") artifact data from its source system and persists it
 * (raw_data_refs). Selected per configuration by moduleCode, not tied to artifactType —
 * any number of adapters can target the same artifactType.
 */
public interface ArtifactAdapter {

    String moduleCode();

    /**
     * Loads the artifact identified by artifactUid, persists the raw snapshot and
     * returns process variables to forward to the next pipeline stage (must include
     * "rawDataRefId").
     */
    Map<String, Object> load(String artifactUid, String sourceId, Map<String, Object> metadata) throws Exception;
}
