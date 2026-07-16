package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.beeline.staging.domain.RawDataContextEntity;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.repository.RawDataContextRepository;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.utils.JsonByteRangeLocator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records where a fragment lives inside an already-stored raw_data_ref, per ADR-005: a primary
 * byte_range (exact, works even once raw_content is compressed) plus a secondary semantic pointer
 * (json_path here — human/UI-readable). Version/relation rows and notices anchor to the resulting
 * row via raw_data_context_id instead of duplicating raw content.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawDataContextService {

    private final RawDataContextRepository repository;
    private final RawDataRefRepository     rawDataRefRepository;
    private final ObjectMapper             objectMapper;

    public Long pointTo(Long rawDataRefId, String jsonPointer) {
        JsonByteRangeLocator.ByteRange byteRange = rawDataRefRepository.findById(rawDataRefId)
                .map(RawDataRef::getRawContent)
                .map(bytes -> JsonByteRangeLocator.locate(bytes, jsonPointer))
                .orElse(null);
        if (byteRange == null) {
            log.debug("Could not locate byte range for pointer={} in rawDataRefId={} — storing secondary-only context",
                    jsonPointer, rawDataRefId);
        }
        return save(rawDataRefId, buildPosition(byteRange, jsonPointer));
    }

    /** No json_path available (free-text notice context, or none at all) — still satisfies the NOT NULL FK on artifact_notices. */
    public Long pointToFreeText(Long rawDataRefId, String description) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("secondary", Map.of("type", "free_text", "value", description == null ? "" : description));
        return save(rawDataRefId, position);
    }

    private Long save(Long rawDataRefId, Map<String, Object> position) {
        RawDataContextEntity entity = new RawDataContextEntity();
        entity.setRawDataRefId(rawDataRefId);
        entity.setPosition(toJson(position));
        return repository.save(entity).getId();
    }

    private Map<String, Object> buildPosition(JsonByteRangeLocator.ByteRange byteRange, String jsonPointer) {
        Map<String, Object> position = new LinkedHashMap<>();
        if (byteRange != null) {
            position.put("primary", Map.of(
                    "type", "byte_range",
                    "value", Map.of("start_offset", byteRange.startOffset(), "end_offset", byteRange.endOffset())));
        }
        position.put("secondary", Map.of(
                "type", "json_path",
                "value", toJsonPathNotation(jsonPointer)));
        return position;
    }

    /** RFC6901 "/diagrams/2/messages/5" -> JSON Path "$.diagrams[2].messages[5]" for human/UI display. */
    private static String toJsonPathNotation(String rfc6901Pointer) {
        StringBuilder sb = new StringBuilder("$");
        for (String segment : rfc6901Pointer.split("/")) {
            if (segment.isEmpty()) continue;
            if (segment.chars().allMatch(Character::isDigit)) {
                sb.append('[').append(segment).append(']');
            } else {
                sb.append('.').append(segment);
            }
        }
        return sb.toString();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
