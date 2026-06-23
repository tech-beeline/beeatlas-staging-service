package ru.beeline.staging.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Resolves which concrete module code should run a given pipeline stage for a given
 * configuration, by reading configurations.config (a JSON map: stage topic name ->
 * moduleCode). This is the only place that interprets that JSON — every worker calls
 * here instead of dispatching by artifactType.
 */
@Component
@RequiredArgsConstructor
public class ModuleResolver {

    private final ConfigurationRepository configurationRepository;
    private final ObjectMapper            objectMapper;

    public String resolve(Long configurationId, String stageKey) {
        Configuration cfg = configurationRepository.findById(configurationId)
                .orElseThrow(() -> new NoSuchElementException("Configuration not found: " + configurationId));
        return resolve(cfg, stageKey);
    }

    public String resolve(Configuration cfg, String stageKey) {
        Map<String, String> moduleMap;
        try {
            moduleMap = objectMapper.readValue(cfg.getConfig(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid config JSON for configurationId=" + cfg.getId(), e);
        }
        String code = moduleMap.get(stageKey);
        if (code == null) {
            throw new IllegalStateException(
                    "No module configured for stage='" + stageKey + "' in configurationId=" + cfg.getId());
        }
        return code;
    }
}
