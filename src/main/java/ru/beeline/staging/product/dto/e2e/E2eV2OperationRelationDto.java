package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

/** fdm-products POST /api/v2/e2e — references operations[].uid directly, no local version ids. */
@Data
public class E2eV2OperationRelationDto {
    private String operationId;
    private String relatedOperationId;
    private Integer order;
    private String stereoType;
}
