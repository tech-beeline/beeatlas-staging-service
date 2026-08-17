package ru.beeline.staging.dto.artifacttype;

import java.time.LocalDateTime;

public record ArtifactTypeSummary(
        Long id,
        String name,
        String dataTypeCode,
        String sourceSystemCode,
        String sourceSystemName,
        LocalDateTime createdAt
) {}
