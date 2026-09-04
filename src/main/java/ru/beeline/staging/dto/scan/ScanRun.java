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
        List<ChildStat> childStats,
        // Stable snapshot taken at fan-out time — unlike childStats (source_artifacts-based), this
        // doesn't reset to empty once a newer scan of the same configuration re-finds the same
        // artifacts. Prefer this field on the frontend; childStats stays for backward compatibility.
        List<ChildStat> childStatsSnapshot
) {}
