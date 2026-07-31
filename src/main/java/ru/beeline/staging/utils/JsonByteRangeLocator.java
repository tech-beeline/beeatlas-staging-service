/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.utils;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;

/**
 * Finds the byte range of the value at an RFC6901 JSON Pointer by streaming the raw bytes rather
 * than re-serializing — offsets are exact against the literally stored raw_data_refs.raw_content.
 */
public final class JsonByteRangeLocator {

    private JsonByteRangeLocator() {}

    public record ByteRange(long startOffset, long endOffset) {}

    private static final JsonFactory FACTORY = new JsonFactory();

    /** Returns null if the pointer doesn't resolve or the bytes aren't parseable JSON. */
    public static ByteRange locate(byte[] jsonBytes, String jsonPointer) {
        if (jsonBytes == null || jsonPointer == null || jsonPointer.isBlank()) return null;
        JsonPointer target;
        try {
            target = JsonPointer.compile(jsonPointer);
        } catch (IllegalArgumentException e) {
            return null;
        }
        try (JsonParser parser = FACTORY.createParser(jsonBytes)) {
            parser.nextToken();
            return find(parser, target, jsonBytes);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Called with the parser positioned ON the current value's first token. Either this value IS
     * the target (pointer exhausted) or we descend into it looking for the next pointer segment.
     */
    private static ByteRange find(JsonParser parser, JsonPointer pointer, byte[] bytes) throws IOException {
        if (pointer.matches()) {
            long start = parser.currentTokenLocation().getByteOffset();
            JsonToken token = parser.currentToken();
            long end;
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                // skipChildren() lands us on the matching END_OBJECT/END_ARRAY, whose location is
                // reliable — the parser has fully scanned through to get there.
                parser.skipChildren();
                end = parser.currentLocation().getByteOffset();
            } else {
                // For scalars, nextToken()/currentLocation() aren't reliably positioned right after
                // the token (e.g. Jackson may not have advanced past a string's closing quote yet) —
                // find the start of whatever comes next instead, then trim back over the separator
                // (whitespace/comma) to the true end of this value.
                JsonToken next = parser.nextToken();
                long nextStart = next != null ? parser.currentTokenLocation().getByteOffset() : bytes.length;
                end = nextStart;
                while (end > start && isSeparator(bytes[(int) (end - 1)])) end--;
            }
            return new ByteRange(start, end);
        }

        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            String wantedField = pointer.getMatchingProperty();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken(); // move to the field's value
                if (field.equals(wantedField)) {
                    return find(parser, pointer.tail(), bytes);
                }
                parser.skipChildren();
            }
            return null;
        }
        if (token == JsonToken.START_ARRAY) {
            int wantedIndex = pointer.getMatchingIndex();
            int index = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (index == wantedIndex) {
                    return find(parser, pointer.tail(), bytes);
                }
                parser.skipChildren();
                index++;
            }
            return null;
        }
        return null;
    }

    private static boolean isSeparator(byte b) {
        return b == ',' || b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }
}
