/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.manual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2ePlantUmlManualOperations implements ArtifactManualOperations {

    public static final String ARTIFACT_TYPE = "e2e-plantuml";

    private final PipelineRunRepository pipelineRunRepository;
    private final ObjectMapper          objectMapper;

    @Override
    public String artifactType() {
        return ARTIFACT_TYPE;
    }

    @Override
    public JsonNode pauseContext(Long runId) {
        String draftJson = pipelineRunRepository.findById(runId)
                .map(PipelineRun::getDraftJson)
                .orElse(null);
        if (draftJson == null || draftJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(draftJson);
        } catch (Exception e) {
            log.warn("Контекст паузы запуска {} не читается как JSON", runId, e);
            return null;
        }
    }

    @Override
    public boolean pauseRequired(Long runId) {
        return true;
    }

    @Override
    public List<String> unmappedParts(Long runId) {
        return List.of();
    }

    @Override
    public void applyDecision(Long runId, ImportDecision decision) {
        throw new PipelineRunBadRequestException("Для типа " + ARTIFACT_TYPE + " решения не принимаются: "
                + "доступны только apply и decline");
    }
}
