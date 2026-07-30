package ru.beeline.staging.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductSummary {
    private String id;
    private String alias;
    private String name;
    private String structurizrApiUrl;
    private String structurizrWorkspaceName;
}
