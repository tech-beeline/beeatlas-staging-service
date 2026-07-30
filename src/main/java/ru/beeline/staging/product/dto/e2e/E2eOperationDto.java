package ru.beeline.staging.product.dto.e2e;

import lombok.Data;

@Data
public class E2eOperationDto {
    // Local reference ids, valid only within this publish payload — see E2eProductDto.id.
    private Long id;
    private Long interfaceVersionId;
    private String uid;
    private String name;
    private String type;
    private String description;
    private String parentInterfaceCode;
    private E2eOperationSlaDto sla;
}
