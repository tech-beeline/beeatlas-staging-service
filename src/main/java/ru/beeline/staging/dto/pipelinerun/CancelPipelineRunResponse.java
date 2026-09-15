/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.pipelinerun;

public record CancelPipelineRunResponse(
        Long runId,
        String status) {
}
