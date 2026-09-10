package ru.beeline.staging.dto.artifact;

import java.time.LocalDateTime;

public record ArtifactByUidResult(
        Long id,
        String extUid,
        String name,
        String status,
        Long artifactTypeId,
        String artifactTypeName,
        String sourceCode,
        String sourceName,
        Long lastRunId,
        Long lastLoadedRefId,
        Long lastSeenScanRunId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}