package ru.beeline.staging.cxbackend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Mirrors cx-backend's {@code ru.beeline.cxbackend.model.CollapsedSubProcess}. */
@Data
public class CollapsedSubProcessDto {
    private String id;
    private String name;
    private List<BiElementDto> biElements = new ArrayList<>();
}
