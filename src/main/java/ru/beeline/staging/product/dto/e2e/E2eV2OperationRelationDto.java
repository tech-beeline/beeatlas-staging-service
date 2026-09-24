package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eV2OperationRelationDto {
    private String operationId;
    private String relatedOperationId;
    private Integer order;
    private String stereoType;
}
