package ru.beeline.staging.dto.search;

import java.time.LocalDateTime;

public record ArtifactSearchResult(
        Long id,
        String extUid,
        String name,
        Long artifactTypeId,
        String status,
        Long lastRunId,
        Long lastSeenScanRunId,
        LocalDateTime updatedAt,
        String foundIn
) {}
