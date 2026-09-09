/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.rundetails;

import java.time.LocalDateTime;

public record ChildPipelineRun(
        Long id,
        String artifactUid,
        String artifactName,
        String artifactType,
        String status,
        Long rawDataRefId,
        String sourceName,
        LocalDateTime startedAt,
        LocalDateTime processingStartedAt,
        LocalDateTime completedAt,
        String failureReason,
        String failedStage,
        /**
         * Since when this run is failed and out of auto-retries, i.e. since when the artifact has
         * stopped being processed and needs a manual retry. NULL for everything else — a run that
         * merely failed still has auto-retries coming and is not stuck.
         */
        LocalDateTime blockedAt
) {}
