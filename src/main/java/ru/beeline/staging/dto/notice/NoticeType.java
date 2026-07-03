package ru.beeline.staging.dto.notice;

import java.time.OffsetDateTime;

public record NoticeType(
        Long id,
        String code,
        String level,
        String category,
        String description,
        String sourceArtifactType,
        String state,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String confirmedBy
) {}
