package ru.beeline.staging.cxbackend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Mirrors cx-backend's {@code ru.beeline.cxbackend.model.BIElement}. */
@Data
public class BiElementDto {
    private String type;
    private String id;
    private String name;
    private String processId;
    private List<CxBiStepDto> biSteps = new ArrayList<>();
}
