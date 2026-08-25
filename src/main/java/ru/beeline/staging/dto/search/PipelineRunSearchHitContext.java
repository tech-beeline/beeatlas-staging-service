package ru.beeline.staging.dto.search;

import com.fasterxml.jackson.databind.JsonNode;

public record PipelineRunSearchHitContext(
        Long id,
        JsonNode position,
        String snippet
) {}
