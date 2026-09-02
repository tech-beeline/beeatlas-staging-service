/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductAliasSummary {
    private Integer id;
    private String name;
    private String alias;
}
