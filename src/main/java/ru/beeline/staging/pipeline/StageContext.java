/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public record StageContext(Long runId, String artifactType, String sourceCode, String branch, JsonNode payload) {

    public static StageContext empty() {
        return new StageContext(null, null, null, null, MissingNode.getInstance());
    }

    public JsonNode payloadOrMissing() {
        return payload == null ? MissingNode.getInstance() : payload;
    }

    public String payloadText(String field) {
        JsonNode value = payloadOrMissing().get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
