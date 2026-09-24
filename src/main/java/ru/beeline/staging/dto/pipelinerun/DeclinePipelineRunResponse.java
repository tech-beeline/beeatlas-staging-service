/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.pipelinerun;

public record DeclinePipelineRunResponse(
        Long runId,
        String status) {
}
