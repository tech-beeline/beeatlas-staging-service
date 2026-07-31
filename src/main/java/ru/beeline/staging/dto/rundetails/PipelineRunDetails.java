/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.rundetails;

import java.time.LocalDateTime;
import java.util.List;

public record PipelineRunDetails(
        Long id,
        String artifactUid,
        String artifactType,
        String status,
        String sourceName,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long batch,
        List<PipelineStageLogDto> stages
) {}
