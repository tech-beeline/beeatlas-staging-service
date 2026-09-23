/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.manual;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.usecase.ImportDecision;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ManualOperations {

    public static final String MODULE_CODE = "manual-operations";

    private static final String DESCRIPTION =
            "Пауза перед публикацией: контекст ручных операций из канонической модели и применение решений";

    private final List<ArtifactManualOperations> handlers;

    private Map<String, ArtifactManualOperations> registry;

    @PostConstruct
    void init() {
        registry = handlers.stream()
                .collect(Collectors.toMap(ArtifactManualOperations::artifactType, h -> h));
        log.info("ManualOperations registry initialized for artifact types: {}", registry.keySet());
    }

    public String moduleCode() {
        return MODULE_CODE;
    }

    public String description() {
        return DESCRIPTION;
    }

    public JsonNode pauseContext(String artifactType, Long runId) {
        ArtifactManualOperations handler = registry.get(artifactType);
        return handler == null ? null : handler.pauseContext(runId);
    }

    public boolean reviewable(String artifactType, Long runId) {
        ArtifactManualOperations handler = registry.get(artifactType);
        return handler == null || handler.pauseContext(runId) != null;
    }

    public List<String> unmappedParts(String artifactType, Long runId) {
        ArtifactManualOperations handler = registry.get(artifactType);
        return handler == null ? List.of() : handler.unmappedParts(runId);
    }

    public void applyDecision(String artifactType, Long runId, ImportDecision decision) {
        ArtifactManualOperations handler = registry.get(artifactType);
        if (handler == null) {
            throw new IllegalStateException("Решения не поддерживаются для типа " + artifactType);
        }
        handler.applyDecision(runId, decision);
    }
}
