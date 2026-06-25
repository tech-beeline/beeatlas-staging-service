package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.PipelineDefinitions;

import java.util.Map;

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
}
