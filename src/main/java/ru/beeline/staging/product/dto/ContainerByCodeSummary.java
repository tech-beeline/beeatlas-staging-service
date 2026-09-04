/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** fdm-products {@code GET /api/v1/container/by-codes} entry — a global, product-agnostic lookup. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContainerByCodeSummary {
    private Integer id;
    private String name;
    private String code;
    private String productAlias;
    private String productName;
}
