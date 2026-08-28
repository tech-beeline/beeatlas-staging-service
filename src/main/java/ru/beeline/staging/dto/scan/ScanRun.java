/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.scan;

import java.time.LocalDateTime;
import java.util.List;

public record ScanRun(
        Long id,
        String code,
        String artifactType,
        String status,
        String displayStatus,
        String sourceName,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<ChildStat> childStats
) {}
