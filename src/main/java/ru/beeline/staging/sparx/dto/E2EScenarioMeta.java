package ru.beeline.staging.sparx.dto;

import lombok.Data;

@Data
public class E2EScenarioMeta {
    private String uid;
    private String name;
    private String version;
    private String processUid;
    private String processName;
    private String notes;
}
