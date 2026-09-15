/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.pipelinerun;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class PipelineRunDecisionsRequest {
    private List<Decision> decisions;

    @Data
    public static class Decision {
        private String partId;
        private String type;
        private JsonNode target;
        private JsonNode newRequest;
    }
}
