package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eV2InterfaceDto {
    private String code;
    private String name;
    private String parentProductCmdb;
    private String specLink;
    private String version;
    private String protocol;
}
