/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.pipelinerun;

public record PipelineRunDecisionsResponse(
        Long runId,
        String status,
        int applied,
        int remaining) {
}
