package ru.beeline.staging.sparx.dto;

import lombok.Data;

@Data
public class MetricQueriesSourceMeta {
    private String uid;
    private String name;
    private String stereotype;
    private String objectType;
    private String apiMetricTemplate;
}
