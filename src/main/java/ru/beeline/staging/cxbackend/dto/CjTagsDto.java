package ru.beeline.staging.cxbackend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Mirrors cx-backend's CJTagsDto (request body for POST /api/cx/v1/product/{productId}/cj). */
@Data
public class CjTagsDto {

    private String name;

    @JsonProperty("draft")
    private Boolean bDraft;
}
