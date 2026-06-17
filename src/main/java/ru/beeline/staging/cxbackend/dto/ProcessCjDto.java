package ru.beeline.staging.cxbackend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Mirrors cx-backend's {@code ru.beeline.cxbackend.model.ProcessCJ} for the import-from-model endpoint. */
@Data
public class ProcessCjDto {
    private String id;
    private List<CollapsedSubProcessDto> collapsedSubProcesses = new ArrayList<>();
    private List<SequenceFlowDto> sequenceFlows = new ArrayList<>();
}
