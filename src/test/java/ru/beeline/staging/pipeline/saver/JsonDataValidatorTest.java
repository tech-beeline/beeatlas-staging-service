package ru.beeline.staging.pipeline.saver;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Юнит-тесты {@link JsonDataValidator} (BLG-004/ADR-011, FR-003-22, FR-003-24).
 *
 * <p>Покрывает три операции утилиты:</p>
 * <ul>
 *   <li>{@code toJsonData} — сериализация Map → JSON-строка, исключение NULL-ключей (FR-003-22)</li>
 *   <li>{@code validate} — валидация json_data как JSON-объекта (FR-003-24, EC-007)</li>
 *   <li>{@code fromJsonData} — десериализация json_data → Map, обработка null/{} (FR-003-22)</li>
 * </ul>
 */
class JsonDataValidatorTest {

    // ---------------------------------------------------------------
    // toJsonData — сериализация Map в JSON-строку
    // ---------------------------------------------------------------

    @Test
    void toJsonData_nullMap_returnsNull() {
        assertThat(JsonDataValidator.toJsonData(null)).isNull();
    }

    @Test
    void toJsonData_emptyMap_returnsNull() {
        assertThat(JsonDataValidator.toJsonData(Map.of())).isNull();
    }

    @Test
    void toJsonData_mapWithOnlyNullValues_returnsNull() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("rps", null);
        attrs.put("watermark", null);
        assertThat(JsonDataValidator.toJsonData(attrs)).isNull();
    }

    @Test
    void toJsonData_mixedMap_nullKeysExcluded() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("rps", 100);
        attrs.put("watermark", null); // должен быть исключён
        attrs.put("latency", 11.2);
        attrs.put("description", "text");

        String json = JsonDataValidator.toJsonData(attrs);

        assertThat(json).isNotNull();
        // NULL-ключ исключён, остальные присутствуют
        assertThat(json)
                .contains("\"rps\":100")
                .contains("\"latency\":11.2")
                .contains("\"description\":\"text\"")
                .doesNotContain("watermark");
    }

    @Test
    void toJsonData_stringMap_serializesAsJsonObject() {
        String json = JsonDataValidator.toJsonData(Map.of("stereotype", "«Обращение»"));
        assertThat(json).isEqualTo("{\"stereotype\":\"«Обращение»\"}");
    }

    // ---------------------------------------------------------------
    // validate — проверка корректности json_data
    // ---------------------------------------------------------------

    @Test
    void validate_null_isValid() {
        // null json_data валидно по BR-18 / FR-003-22 (нет неосновных атрибутов)
        assertThatCode(() -> JsonDataValidator.validate(null)).doesNotThrowAnyException();
    }

    @Test
    void validate_validJsonObject_accepts() {
        assertThatCode(() -> JsonDataValidator.validate("{\"rps\":100,\"description\":\"x\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_emptyJsonObject_accepts() {
        assertThatCode(() -> JsonDataValidator.validate("{}")).doesNotThrowAnyException();
    }

    @Test
    void validate_notValidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> JsonDataValidator.validate("not-a-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void validate_jsonArray_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> JsonDataValidator.validate("[1,2,3]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected JSON object");
    }

    @Test
    void validate_jsonScalar_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> JsonDataValidator.validate("\"just a string\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected JSON object");
    }

    // ---------------------------------------------------------------
    // fromJsonData — десериализация json_data в Map
    // ---------------------------------------------------------------

    @Test
    void fromJsonData_null_returnsEmptyMap() {
        assertThat(JsonDataValidator.fromJsonData(null)).isEmpty();
    }

    @Test
    void fromJsonData_blank_returnsEmptyMap() {
        assertThat(JsonDataValidator.fromJsonData("   ")).isEmpty();
    }

    @Test
    void fromJsonData_emptyObject_returnsEmptyMap() {
        assertThat(JsonDataValidator.fromJsonData("{}")).isEmpty();
    }

    @Test
    void fromJsonData_object_returnsTypedMap() {
        Map<String, Object> result = JsonDataValidator.fromJsonData(
                "{\"rps\":100,\"latency\":11.2,\"enabled\":true,\"description\":\"text\",\"big\":2147483648}");

        assertThat(result)
                .containsEntry("rps", 100)
                .containsEntry("latency", 11.2)
                .containsEntry("enabled", true)
                .containsEntry("description", "text")
                .containsEntry("big", 2147483648L);
    }

    @Test
    void fromJsonData_withNullValue_keepsNullEntry() {
        Map<String, Object> result = JsonDataValidator.fromJsonData("{\"watermark\":null}");

        assertThat(result).containsKey("watermark");
        assertThat(result.get("watermark")).isNull();
    }

    @Test
    void fromJsonData_nestedObject_returnsRawJsonString() {
        Map<String, Object> result = JsonDataValidator.fromJsonData("{\"nested\":{\"a\":1}}");

        assertThat(result)
                .containsEntry("nested", "{\"a\":1}");
    }

    @Test
    void fromJsonData_nestedArray_returnsRawJsonString() {
        Map<String, Object> result = JsonDataValidator.fromJsonData("{\"list\":[1,2,3]}");

        assertThat(result)
                .containsEntry("list", "[1,2,3]");
    }

    @Test
    void fromJsonData_notValidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> JsonDataValidator.fromJsonData("broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void fromJsonData_nonObject_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> JsonDataValidator.fromJsonData("[1,2]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected JSON object");
    }

    // ---------------------------------------------------------------
    // round-trip: toJsonData -> fromJsonData
    // ---------------------------------------------------------------

    @Test
    void roundTrip_serializeThenDeserialize_preservesValues() {
        Map<String, Object> original = new HashMap<>();
        original.put("rps", 100);
        original.put("latency", 11.2);
        original.put("description", "test");

        String json = JsonDataValidator.toJsonData(original);
        Map<String, Object> restored = JsonDataValidator.fromJsonData(json);

        assertThat(restored).isEqualTo(original);
    }
}