package ru.beeline.staging.pipeline.adapter;

import java.util.Map;

public interface ArtifactAdapter {

    String moduleCode();

    String description();

    Map<String, Object> load(String artifactUid, String sourceId, Map<String, Object> metadata) throws Exception;
}
