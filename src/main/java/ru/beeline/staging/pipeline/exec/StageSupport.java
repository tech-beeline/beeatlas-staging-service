/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.SourceSystem;
import ru.beeline.staging.pipeline.StageContext;
import ru.beeline.staging.repository.SourceSystemRepository;

import java.util.Map;
import java.util.stream.Collectors;

public final class StageSupport {

    private StageSupport() {
    }

    public static Map<String, Object> buildSummary(Map<String, Object> outputVars) {
        if (outputVars == null || outputVars.isEmpty()) return null;
        return outputVars.entrySet().stream()
                .filter(e -> e.getValue() instanceof Number || e.getValue() instanceof Boolean)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public static StageContext contextOf(PipelineRun run, ObjectMapper objectMapper,
            SourceSystemRepository sourceSystemRepository) {
        JsonNode payload = MissingNode.getInstance();
        if (run.getPayload() != null && !run.getPayload().isBlank()) {
            try {
                payload = objectMapper.readTree(run.getPayload());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse pipeline_runs.payload for runId=" + run.getId(), e);
            }
        }
        String sourceCode = run.getSourceId() == null ? null
                : sourceSystemRepository.findById(run.getSourceId()).map(SourceSystem::getCode).orElse(null);
        return new StageContext(run.getId(), run.getArtifactType(), sourceCode, run.getBranch(), payload);
    }
}
