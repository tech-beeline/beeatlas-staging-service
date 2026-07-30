package ru.beeline.staging.dto.rundetails;

import java.time.LocalDateTime;

public record ChildPipelineRun(
        Long id,
        String artifactUid,
        String artifactType,
        String status,
        String sourceName,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String failureReason,
        String failedStage
) {}
