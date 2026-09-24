package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class JsonDataValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonDataValidator() {
    }

    public static String toJsonData(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return null;
        }
        Map<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (e.getValue() != null) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        if (filtered.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(filtered);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize json_data from attributes: " + attrs, ex);
        }
    }

    public static void validate(String jsonData) {
        if (jsonData == null) {
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(jsonData);
            if (!node.isObject()) {
                throw new IllegalArgumentException(
                        "Invalid json_data: expected JSON object but got " + node.getNodeType()
                                + " — value: " + jsonData.substring(0, Math.min(200, jsonData.length())));
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    "Invalid json_data: not valid JSON — " + ex.getMessage()
                            + " — value: " + jsonData.substring(0, Math.min(200, jsonData.length())), ex);
        }
    }

    public static Map<String, Object> fromJsonData(String jsonData) {
        if (jsonData == null || jsonData.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            JsonNode node = MAPPER.readTree(jsonData);
            if (!node.isObject()) {
                throw new IllegalArgumentException(
                        "Invalid json_data: expected JSON object but got " + node.getNodeType());
            }
            if (node.size() == 0) {
                return Collections.emptyMap();
            }
            Map<String, Object> result = new HashMap<>();
            ((ObjectNode) node).fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                result.put(key, convertJsonNode(value));
            });
            return result;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    "Invalid json_data: not valid JSON — " + ex.getMessage(), ex);
        }
    }

    private static Object convertJsonNode(JsonNode node) {
        if (node.isNull()) {
            return null;
        } else if (node.isTextual()) {
            return node.asText();
        } else if (node.isInt()) {
            return node.asInt();
        } else if (node.isLong()) {
            return node.asLong();
        } else if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isNumber()) {
            return node.decimalValue();
        } else {
            try {
                return MAPPER.writeValueAsString(node);
            } catch (JsonProcessingException e) {
                return node.asText();
            }
        }
    }
}
