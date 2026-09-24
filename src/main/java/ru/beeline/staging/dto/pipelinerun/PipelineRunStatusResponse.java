/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.pipelinerun;

import com.fasterxml.jackson.databind.JsonNode;

public record PipelineRunStatusResponse(
        Long runId,
        String artifactType,
        String artifactUid,
        String status,
        String stage,
        int noticesCount,
        JsonNode result,
        boolean more) {

    public static PipelineRunStatusResponse of(PipelineRunStatusSnapshot snapshot, boolean more) {
        return new PipelineRunStatusResponse(snapshot.runId(), snapshot.artifactType(), snapshot.artifactUid(),
                snapshot.status(), snapshot.stage(), snapshot.noticesCount(), snapshot.result(), more);
    }
}
