/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MatchedArchOperation {

    private Integer id;
    private String name;
    private String type;
    private String productCode;
    private String error;
    private Boolean notFound;

    @JsonProperty("interface")
    private Ref interfaceObj;

    private Ref container;
    private ProductRef product;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ref {
        private Integer id;
        private String name;
        private String code;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductRef {
        private Integer id;
        private String name;
        private String alias;
    }
}
