package ru.beeline.staging.dto.search;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record NoticeSearchResult(
        Long id,
        Long noticeTypeId,
        String code,
        String level,
        String category,
        String description,
        String details,
        Long rawDataRefId,
        Long rawDataContextId,
        JsonNode position,
        LocalDateTime createdAt,
        String foundIn
) {}
