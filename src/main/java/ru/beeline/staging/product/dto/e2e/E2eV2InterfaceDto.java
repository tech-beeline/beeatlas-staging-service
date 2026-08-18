package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

/** Interface linked directly to a product (no containers layer) — fdm-products POST /api/v2/e2e. */
@Data
public class E2eV2InterfaceDto {
    private String code;
    private String name;
    private String parentProductCmdb;
    private String specLink;
    private String version;
    private String protocol;
}
