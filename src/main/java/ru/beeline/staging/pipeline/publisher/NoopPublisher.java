/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.publisher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class NoopPublisher implements ArtifactPublisher {

    public static final String MODULE_CODE = "noop-publisher";

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Artifact types that are not published outside staging"; }

    @Override
    public Map<String, Object> publish(String artifactUid, String artifactType, long rawDataRefId, Long runId) {
        log.info("stage=publisher, module={}, uid={} — публикация для типа {} не предусмотрена",
                MODULE_CODE, artifactUid, artifactType);
        return Map.of("published", false);
    }
}
