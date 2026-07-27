package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eInterfaceDto {
    // Local reference ids, valid only within this publish payload — see E2eProductDto.id.
    private Long interfaceVersionId;
    private Long containerVersionId;
    private String code;
    private String name;
    private String parentContainerCode;
    private String specLink;
    private String version;
    private String protocol;
}
