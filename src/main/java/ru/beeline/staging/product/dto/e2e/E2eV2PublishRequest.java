package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

import java.util.List;

/**
 * Body for fdm-products {@code POST /api/v2/e2e} — Sparx-sourced e2e ingested directly into the
 * product catalog (discovered_interface/discovered_operation), no containers layer. {@code e2e} and
 * {@code products} reuse the same shape as v1 (E2eInfoDto/E2eProductDto).
 */
@Data
public class E2eV2PublishRequest {
    private E2eInfoDto e2e;
    private List<E2eProductDto> products;
    private List<E2eV2InterfaceDto> interfaces;
    private List<E2eV2OperationDto> operations;
    private List<E2eV2OperationRelationDto> operationsRelations;
}
