/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eInfoDto {
    private String uid;
    private String name;
    private String description;
    private String biStepCode;
}
