package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.PipelineDefinitions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves which concrete module code should run a given pipeline stage for a given
 * artifactType, by reading PipelineDefinitions.moduleMapFor(artifactType) — this is the
 * only place that interprets that map; every worker calls here instead of dispatching by
 * artifactType itself.
 */
@Component
@RequiredArgsConstructor
public class ModuleResolver {

    private final PipelineDefinitions pipelineDefinitions;

    public String resolve(String artifactType, String stageKey) {
        Map<String, String> moduleMap = pipelineDefinitions.moduleMapFor(artifactType);
        if (moduleMap == null) {
            throw new IllegalStateException("No PipelineDefinitions entry for artifactType=" + artifactType);
        }
        String code = moduleMap.get(stageKey);
        if (code == null) {
            throw new IllegalStateException(
                    "No module configured for stage='" + stageKey + "' in artifactType=" + artifactType);
        }
        return code;
    }

    /** moduleCodes for the given stages, in PipelineDefinitions.STAGE_ORDER order, skipping any
     *  stage not configured for this artifactType — used to snapshot the planned sequence onto
     *  a pipeline_run. */
    public List<String> resolveSequence(String artifactType, List<String> stages) {
        Map<String, String> moduleMap = pipelineDefinitions.moduleMapFor(artifactType);
        if (moduleMap == null) {
            return List.of();
        }
        return PipelineDefinitions.STAGE_ORDER.stream()
                .filter(stages::contains)
                .map(moduleMap::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
