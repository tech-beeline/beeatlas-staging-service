package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MetricQueriesObjectPublish(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("generated_at") String generatedAt,
        @JsonProperty("entity_type") String entityType,
        @JsonProperty("uid") String uid,
        @JsonProperty("metricTemplates") List<MetricTemplate> metricTemplates
) {}
