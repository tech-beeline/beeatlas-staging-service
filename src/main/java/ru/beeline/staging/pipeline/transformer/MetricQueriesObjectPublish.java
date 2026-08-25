package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Canonical snapshot of one metric-queries source object — matches
 * documentation/dashboard-service/model/schemas/metric-queries-snapshot-schema.md (schema_version 1.0).
 * Field names are the external publish contract, pinned with @JsonProperty (see MetricTemplate).
 */
public record MetricQueriesObjectPublish(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("generated_at") String generatedAt,
        @JsonProperty("entity_type") String entityType,
        @JsonProperty("uid") String uid,
        @JsonProperty("metricTemplates") List<MetricTemplate> metricTemplates
) {}
