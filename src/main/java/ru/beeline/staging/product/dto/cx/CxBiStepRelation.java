/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.cx;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CxBiStepRelation {
    private Integer id;
    private String description;
    private Integer productId;
    private Integer tcId;
    private Integer operationId;
    private Integer interfaceId;
}
