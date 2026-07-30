package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eOperationRelationDto {
    // Local reference ids, valid only within this publish payload — see E2eProductDto.id.
    private Long operationVersionId;
    private Long relatedOperationVersionId;
    private String operationId;
    private String relatedOperationId;
    private Integer order;
    private String stereoType;
}
