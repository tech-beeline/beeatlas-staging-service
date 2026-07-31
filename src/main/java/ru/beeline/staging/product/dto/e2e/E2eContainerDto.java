/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eContainerDto {

    private Long containerVersionId;
    private Long productVersionId;
    private String code;
    private String name;
    private String parentProductCmdb;
}
