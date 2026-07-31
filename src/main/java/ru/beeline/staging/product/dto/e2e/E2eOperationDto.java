/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eOperationDto {

    private Long id;
    private Long interfaceVersionId;
    private String uid;
    private String name;
    private String type;
    private String description;
    private String parentInterfaceCode;
    private E2eOperationSlaDto sla;
}
