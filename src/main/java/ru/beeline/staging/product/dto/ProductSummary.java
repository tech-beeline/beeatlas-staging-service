package ru.beeline.staging.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Mirrors the fields the structurizr-sequence pipeline needs out of fdm-products'
 * ProductInfoShortDTO (GET /api/v1/product/info) and ProductInfoDTO (GET /api/v1/product/{code}/info)
 * — ignoreUnknown since the two response shapes aren't identical (techProducts, description, etc.).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductSummary {
    private String id;
    private String alias;
    private String name;
    private String structurizrApiUrl;
    private String structurizrWorkspaceName;
}
