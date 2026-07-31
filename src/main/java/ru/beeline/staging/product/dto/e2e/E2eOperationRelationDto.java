/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eOperationRelationDto {

    private Long operationVersionId;
    private Long relatedOperationVersionId;
    private String operationId;
    private String relatedOperationId;
    private Integer order;
    private String stereoType;
}
