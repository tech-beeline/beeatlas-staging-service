/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.rundetails;

import java.time.LocalDateTime;
import java.util.Map;

public record PipelineStageLogDto(
        Long id,
        String stageName,
        String status,
        String inputData,
        String outputData,
        Map<String, Object> summaryJson,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String failureReason
) {}
