package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Утилита валидации и сериализации {@code json_data} для версий канонических сущностей (BLG-004/ADR-011).
 *
 * <p>Предоставляет три операции:</p>
 * <ul>
 *   <li>{@link #toJsonData(Map)} — сериализация Map → JSON-строка (ключи snake_case, NULL исключены)</li>
 *   <li>{@link #validate(String)} — проверка корректности json_data как JSON-объекта (FR-003-24, EC-007)</li>
 *   <li>{@link #fromJsonData(String)} — десериализация json_data → Map (null/{} → пустая Map, FR-003-22)</li>
 * </ul>
 *
 * <p>Используется MatchService-ами (CMP-03) перед {@code setJsonData()} и при чтении (CMP-06).</p>
 */
public final class JsonDataValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonDataValidator() {
        // utility class
    }

    /**
     * Сериализует Map неосновных атрибутов в JSON-строку для колонки {@code json_data}.
     *
     * <p>NULL-значения исключаются из результирующего JSON (соответствует FR-003-22/ADR-011 §1).</p>
     *
     * @param attrs карта атрибутов (ключи snake_case); {@code null} или пустая Map → {@code null}
     * @return JSON-строка (например, {@code {"rps":100,"latency":11.2}}) или {@code null}, если вход пуст
     */
    public static String toJsonData(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return null;
        }
        // Filter out null values — absence of key = absence of value (FR-003-22, ADR-011 §1)
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

    /**
     * Валидирует содержимое {@code json_data} как корректный JSON-объект (FR-003-24, EC-007).
     *
     * <p>Вызов данного метода — 2-я линия защиты валидации (первая — CHECK-constraint на уровне БД).</p>
     *
     * @param jsonData JSON-строка для проверки; {@code null} допускается (пустое json_data валидно по BR-18)
     * @throws IllegalArgumentException если строка не является корректным JSON-объектом
     */
    public static void validate(String jsonData) {
        if (jsonData == null) {
            // null json_data is valid — means no non-primary attributes (FR-003-22, BR-18)
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

    /**
     * Десериализует {@code json_data} в Map неосновных атрибутов (FR-003-22).
     *
     * <p>{@code null} или пустой JSON-объект ({@code {}}) возвращают пустую Map без исключения.</p>
     *
     * @param jsonData JSON-строка; может быть {@code null} или пустой
     * @return карта атрибутов (никогда не {@code null})
     * @throws IllegalArgumentException если строка не является корректным JSON-объектом
     */
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

    /**
     * Преобразует JsonNode в Java-значение.
     */
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
            // For nested objects/arrays, return raw JSON string
            try {
                return MAPPER.writeValueAsString(node);
            } catch (JsonProcessingException e) {
                return node.asText();
            }
        }
    }
}
