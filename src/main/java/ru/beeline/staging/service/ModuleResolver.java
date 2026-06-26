package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.PipelineDefinitions;

import java.util.Map;

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
