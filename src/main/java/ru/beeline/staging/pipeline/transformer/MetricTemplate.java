package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One record of MetricQueriesObjectPublish.metricTemplates — metric_code + Grafana target JSON
 * (CM-02-01). Field names are the external publish contract (snake_case metric_code) — not the
 * project's usual camelCase API convention, so they're pinned with @JsonProperty.
 */
public record MetricTemplate(
        @JsonProperty("metric_code") String metricCode,
        @JsonProperty("template") JsonNode template
) {}
