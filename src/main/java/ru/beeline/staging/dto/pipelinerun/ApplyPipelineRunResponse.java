/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.pipelinerun;

public record ApplyPipelineRunResponse(
        Long runId,
        String status,
        String statusUrl) {
}
