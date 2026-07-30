package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

import java.util.List;

/**
 * Body for fdm-products {@code POST /api/v1/e2e}. Field names/casing mirror the target
 * contract exactly — see ea-e2e-sequence-save-spec.md §2.2.
 */
@Data
public class E2ePublishRequest {
    private E2eInfoDto e2e;
    private List<E2eProductDto> products;
    private List<E2eContainerDto> containers;
    private List<E2eInterfaceDto> interfaces;
    private List<E2eOperationDto> operations;
    private List<E2eOperationRelationDto> operationsRelations;
}
