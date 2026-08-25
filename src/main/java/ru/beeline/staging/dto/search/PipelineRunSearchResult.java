package ru.beeline.staging.dto.search;

import java.time.LocalDateTime;
import java.util.List;

public record PipelineRunSearchResult(
        Long id,
        String artifactUid,
        String artifactName,
        String artifactType,
        String status,
        LocalDateTime startedAt,
        Long rawDataRefId,
        int count,
        List<PipelineRunSearchHit> hits
) {}
