package ru.beeline.staging.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JsonByteRangeLocatorTest {

    @Test
    void locatesTopLevelArrayElementByIndex() {
        String json = "{\"interfaces\":[{\"id\":1},{\"id\":2},{\"id\":3}]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        JsonByteRangeLocator.ByteRange range = JsonByteRangeLocator.locate(bytes, "/interfaces/1");

        assertThat(range).isNotNull();
        String extracted = new String(bytes, (int) range.startOffset(), (int) (range.endOffset() - range.startOffset()), StandardCharsets.UTF_8);
        assertThat(extracted).isEqualTo("{\"id\":2}");
    }

    @Test
    void locatesNestedFieldInsideArrayElement() {
        String json = "{\"diagrams\":[{\"uid\":\"D1\",\"messages\":[{\"uid\":\"M1\"},{\"uid\":\"M2\"}]}]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        JsonByteRangeLocator.ByteRange range = JsonByteRangeLocator.locate(bytes, "/diagrams/0/messages/1");

        assertThat(range).isNotNull();
        String extracted = new String(bytes, (int) range.startOffset(), (int) (range.endOffset() - range.startOffset()), StandardCharsets.UTF_8);
        assertThat(extracted).isEqualTo("{\"uid\":\"M2\"}");
    }

    @Test
    void locatesScalarField() {
        String json = "{\"entrance_diagram_uid\":\"D1\",\"diagrams\":[]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        JsonByteRangeLocator.ByteRange range = JsonByteRangeLocator.locate(bytes, "/entrance_diagram_uid");

        assertThat(range).isNotNull();
        String extracted = new String(bytes, (int) range.startOffset(), (int) (range.endOffset() - range.startOffset()), StandardCharsets.UTF_8);
        assertThat(extracted).isEqualTo("\"D1\"");
    }

    @Test
    void returnsNullWhenPointerDoesNotResolve() {
        byte[] bytes = "{\"a\":[1,2]}".getBytes(StandardCharsets.UTF_8);

        assertThat(JsonByteRangeLocator.locate(bytes, "/a/5")).isNull();
        assertThat(JsonByteRangeLocator.locate(bytes, "/missing")).isNull();
    }
}
