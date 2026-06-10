package ru.beeline.staging.consumer.dto;

import lombok.Data;

@Data
public class StagingEvent {
    private String artifactType;
    private String artifactUid;
    private String sourceId;
}
