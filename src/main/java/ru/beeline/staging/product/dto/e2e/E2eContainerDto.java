package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eContainerDto {
    private String code;
    private String name;
    private String parentProductCmdb;
}
