package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eProductDto {
    // Staging's product_versions.id — a local reference valid only within this publish payload
    // (resolves ambiguous code-based matching on the fdm-products side), not a persistent external id.
    private Long id;
    private String cmdb;
    private String name;
}
