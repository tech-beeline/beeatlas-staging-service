package ru.beeline.staging.dto.scan;

import java.time.LocalDateTime;
import java.util.List;

public record ScanRunDetails(
        Long id,
        String code,
        String artifactType,
        String status,
        String displayStatus,
        String sourceName,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<ChildStat> childStats,
        List<ChildStat> childStatsSnapshot
) {}
