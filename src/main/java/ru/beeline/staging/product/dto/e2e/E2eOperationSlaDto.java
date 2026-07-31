/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eOperationSlaDto {
    private Double rps;
    private Double latency;
    private Double errorRate;
}
