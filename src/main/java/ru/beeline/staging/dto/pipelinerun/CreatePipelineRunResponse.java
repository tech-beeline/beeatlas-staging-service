/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.pipelinerun;

public record CreatePipelineRunResponse(
        Long runId,
        String artifactType,
        String artifactUid,
        String status,
        String statusUrl) {
}
