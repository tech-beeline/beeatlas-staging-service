/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.sparx.dto;

import lombok.Data;

@Data
public class E2EScenarioMeta {
    private String uid;
    private String name;
    private String version;
    private String notes;
}
