/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

import java.util.List;


@Data
public class E2ePublishRequest {
    private E2eInfoDto e2e;
    private List<E2eProductDto> products;
    private List<E2eContainerDto> containers;
    private List<E2eInterfaceDto> interfaces;
    private List<E2eOperationDto> operations;
    private List<E2eOperationRelationDto> operationsRelations;
}
