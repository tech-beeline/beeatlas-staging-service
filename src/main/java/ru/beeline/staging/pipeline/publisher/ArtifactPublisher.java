/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.publisher;

import java.util.Map;

public interface ArtifactPublisher {

    String moduleCode();

    String description();

    Map<String, Object> publish(String artifactUid, String artifactType, long rawDataRefId, Long runId)
            throws Exception;
}
