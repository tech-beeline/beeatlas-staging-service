package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eInterfaceDto {
    private String code;
    private String name;
    private String parentContainerCode;
    private String specLink;
    private String version;
    private String protocol;
}
