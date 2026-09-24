/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.adapter;

import ru.beeline.staging.pipeline.StageContext;

import java.util.Map;

public interface ArtifactAdapter {

    String moduleCode();

    String description();

    Map<String, Object> load(String artifactUid, String sourceId, StageContext context) throws Exception;
}
