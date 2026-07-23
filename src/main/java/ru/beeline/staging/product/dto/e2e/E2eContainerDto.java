package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eContainerDto {
    // Local reference ids, valid only within this publish payload — see E2eProductDto.id.
    private Long id;
    private Long productVersionId;
    private String code;
    private String name;
    private String parentProductCmdb;
}
