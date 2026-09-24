package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record MetricTemplate(
        @JsonProperty("metric_code") String metricCode,
        @JsonProperty("template") JsonNode template
) {}
