package ru.beeline.staging.dto.notice;

import java.util.UUID;

public record ArtifactNotice(
        Long id,
        Long noticeTypeId,
        String code,
        String level,
        String category,
        Long rawDataRefId,
        String entityType,
        String entityUid,
        Long entityVersionId,
        String message,
        String details,
        String context,
        UUID rawDataContextId
) {}
