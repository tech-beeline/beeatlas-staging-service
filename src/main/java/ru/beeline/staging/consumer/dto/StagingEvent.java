package ru.beeline.staging.consumer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

@Data
public class StagingEvent {
    private String artifactType;
    private String artifactUid;
    private String sourceId;
    private Long   configurationId;
    private String batchId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> metadata;
}
