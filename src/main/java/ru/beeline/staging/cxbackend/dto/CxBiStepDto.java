package ru.beeline.staging.cxbackend.dto;

import lombok.Data;

/** Mirrors cx-backend's {@code ru.beeline.cxbackend.model.BiStep} (named Cx-prefixed to avoid clashing with staging's own canonical BiStep entity). */
@Data
public class CxBiStepDto {
    private String type;
    private String id;
    private String name;
}
