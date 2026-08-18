package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

/** fdm-products POST /api/v2/e2e — no local reference ids (unlike v1's operationVersionId etc). */
@Data
public class E2eV2OperationDto {
    private String uid;
    private String name;
    private String type;
    private String description;
    private String parentInterfaceCode;
    private E2eOperationSlaDto sla;
}
