/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ru.beeline.staging.domain.RawDataContextEntity;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.repository.RawDataContextRepository;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.utils.JsonByteRangeLocator;

import java.util.LinkedHashMap;
import java.util.Map;

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

    public Long pointToFreeText(Long rawDataRefId, String description) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("secondary", Map.of("type", "free_text", "value", description == null ? "" : description));
        return save(rawDataRefId, position);
    }

    private Long save(Long rawDataRefId, Map<String, Object> position) {
        String positionJson = toJson(position);
        return repository.findFirstByRawDataRefIdAndPositionOrderByIdAsc(rawDataRefId, positionJson)
                .map(RawDataContextEntity::getId)
                .orElseGet(() -> {
                    RawDataContextEntity entity = new RawDataContextEntity();
                    entity.setRawDataRefId(rawDataRefId);
                    entity.setPosition(positionJson);
                    try {
                        return repository.save(entity).getId();
                    } catch (DataIntegrityViolationException e) {
                        return repository.findFirstByRawDataRefIdAndPositionOrderByIdAsc(rawDataRefId, positionJson)
                                .map(RawDataContextEntity::getId)
                                .orElseThrow(() -> e);
                    }
                });
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
