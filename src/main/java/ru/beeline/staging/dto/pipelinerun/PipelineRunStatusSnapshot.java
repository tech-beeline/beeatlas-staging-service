/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.pipelinerun;

import com.fasterxml.jackson.databind.JsonNode;

public record PipelineRunStatusSnapshot(
        Long runId,
        String artifactType,
        String artifactUid,
        String status,
        String stage,
        int noticesCount,
        JsonNode result) {
}
