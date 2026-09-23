/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.manual;

import com.fasterxml.jackson.databind.JsonNode;
import ru.beeline.staging.dto.usecase.ImportDecision;

import java.util.List;

public interface ArtifactManualOperations {

    String artifactType();

    JsonNode pauseContext(Long runId);

    List<String> unmappedParts(Long runId);

    void applyDecision(Long runId, ImportDecision decision);
}
