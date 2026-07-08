package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.beeline.staging.domain.RawDataContextEntity;
import ru.beeline.staging.repository.RawDataContextRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Records a json_path pointer into an already-stored raw_data_ref, so version/relation rows and
 * notices can anchor to the exact fragment they came from without duplicating raw content.
 */
@Service
@RequiredArgsConstructor
public class RawDataContextService {

    private final RawDataContextRepository repository;
    private final ObjectMapper             objectMapper;

    public UUID pointTo(Long rawDataRefId, String jsonPath) {
        RawDataContextEntity entity = new RawDataContextEntity();
        entity.setRawDataRefId(rawDataRefId);
        entity.setFormat("json");
        entity.setNavigationType("json_path");
        entity.setPosition(toJson(Map.of("path", jsonPath)));
        return repository.save(entity).getId();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
