/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.product.dto.e2e.E2ePublishRequest;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2eProductsPublisher {

    private final ActualE2eScenarioRepository actualE2eScenarioRepository;
    private final E2ePublishRequestMapper e2ePublishRequestMapper;
    private final E2eProductsClient e2eProductsClient;
    private final ArtifactNoticeService artifactNoticeService;
    private final ObjectMapper objectMapper;

    public void publish(String artifactUid, Long rawDataRefId) {
        String actualScenarioJson = actualE2eScenarioRepository.fetchActualScenarioRaw(artifactUid);
        if (actualScenarioJson == null) {
            log.warn("No actual e2e_scenario state found for uid={} — skipping fdm-products publish", artifactUid);
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(actualScenarioJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse actual e2e scenario JSON for uid=" + artifactUid, e);
        }

        if (root.path("e2e").path("uid").isMissingNode() || root.path("e2e").path("uid").isNull()) {
            log.warn("Actual e2e_scenario state for uid={} has no e2e block — skipping fdm-products publish", artifactUid);
            return;
        }

        E2ePublishRequest request = e2ePublishRequestMapper.map(root);
        try {
            e2eProductsClient.upsertE2e(request);
        } catch (RuntimeException e) {
            recordPublishFailure(artifactUid, rawDataRefId, e);
            throw e;
        }
    }

    private void recordPublishFailure(String artifactUid, Long rawDataRefId, Exception e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("uid", artifactUid);
        details.put("error", e.getMessage());
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception jsonEx) {
            detailsJson = "{}";
        }
        ArtifactNotice notice = new ArtifactNotice(null, null, "publish.failed", "error", "publish",
                rawDataRefId, "e2e_scenario", artifactUid, null, "publish.failed", detailsJson, null, null);
        artifactNoticeService.saveNoticeInNewTransaction(rawDataRefId, notice);
    }
}
