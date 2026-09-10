package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

import java.util.List;

@Data
public class E2eV2PublishRequest {
    private E2eInfoDto e2e;
    private List<E2eProductDto> products;
    private List<E2eV2InterfaceDto> interfaces;
    private List<E2eV2OperationDto> operations;
    private List<E2eV2OperationRelationDto> operationsRelations;
}
