/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dashboard.DashboardServicePublishClient;
import ru.beeline.staging.pipeline.transformer.MetricQueriesObjectPublish;
import ru.beeline.staging.repository.RawDataRefRepository;

import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueriesPublisher implements ArtifactPublisher {

    public static final String MODULE_CODE = "metric-queries-publisher";

    private final DashboardServicePublishClient publishClient;
    private final RawDataRefRepository rawDataRefRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Publishes metric query templates to dashboard-service"; }

    @Override
    public Map<String, Object> publish(String artifactUid, String artifactType, long rawDataRefId, Long runId)
            throws Exception {
        String canonicalSnapshotJson = rawDataRefRepository.findById(rawDataRefId)
                .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId))
                .getCanonicalSnapshotJson();
        if (canonicalSnapshotJson == null || canonicalSnapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to publish", artifactUid);
            return Map.of("published", false);
        }
        MetricQueriesObjectPublish snapshot =
                objectMapper.readValue(canonicalSnapshotJson, MetricQueriesObjectPublish.class);
        boolean published = publishClient.publish(snapshot, rawDataRefId);
        return Map.of("published", published);
    }
}
