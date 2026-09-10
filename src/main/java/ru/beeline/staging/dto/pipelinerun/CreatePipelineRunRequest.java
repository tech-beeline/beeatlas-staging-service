/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.pipelinerun;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class CreatePipelineRunRequest {
    private String artifactType;
    private String artifactUid;
    private String source;
    private JsonNode payload;
    private Long supersedesRunId;
    private String branch;
}
