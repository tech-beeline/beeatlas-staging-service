/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OperationEntry {
    private Integer id;
    private String name;
    private String type;
    private ProductRef product;
    private ContainerRef container;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductRef {
        private Integer id;
        private String name;
        private String alias;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContainerRef {
        private Integer id;
        private String name;
        private String code;
    }
}
