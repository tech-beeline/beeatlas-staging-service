package ru.beeline.staging.cxbackend.dto;

import lombok.Data;

/** Mirrors cx-backend's CjResponseDto (response body of POST /api/cx/v1/product/{productId}/cj). */
@Data
public class CjResponseDto {
    private Long id;
    private String name;
}
