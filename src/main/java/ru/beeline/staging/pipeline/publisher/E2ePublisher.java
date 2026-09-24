/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.publisher;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.beeline.staging.product.E2eProductsPublisher;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class E2ePublisher implements ArtifactPublisher {

    public static final String MODULE_CODE = "e2e-publisher";

    private final E2eProductsPublisher e2eProductsPublisher;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Publishes the canonical e2e scenario to fdm-products and cx-backend"; }

    @Override
    public Map<String, Object> publish(String artifactUid, String artifactType, long rawDataRefId, Long runId) {
        e2eProductsPublisher.publish(artifactUid, artifactType, rawDataRefId, runId);
        return Map.of("published", true);
    }
}
