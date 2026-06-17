package ru.beeline.staging.cxbackend.dto;

import lombok.Data;

/** Mirrors cx-backend's {@code ru.beeline.cxbackend.model.SequenceFlow}. */
@Data
public class SequenceFlowDto {
    private String id;
    private String sourceRef;
    private String targetRef;
}
