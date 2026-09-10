/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.client.E2eProductsClient;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.product.dto.e2e.E2ePublishResponse;
import ru.beeline.staging.product.dto.e2e.E2eV2PublishRequest;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2eProductsPublisher {

    private final ActualE2eScenarioRepository actualE2eScenarioRepository;
    private final E2eV2PublishRequestMapper e2ePublishRequestMapper;
    private final E2eProductsClient e2eProductsClient;
    private final CxBiStepRelationsPublisher cxBiStepRelationsPublisher;
    private final ArtifactNoticeService artifactNoticeService;
    private final ObjectMapper objectMapper;

    public void publish(String artifactUid, String artifactType, Long rawDataRefId, Long pipelineRunId) {
        String actualScenarioJson = actualE2eScenarioRepository.fetchActualScenarioRaw(artifactUid);
        if (actualScenarioJson == null) {
            log.warn("No actual e2e_scenario state found for uid={}, relationId={}, pipelineRunId={} — skipping fdm-products publish",
                    artifactUid, rawDataRefId, pipelineRunId);
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(actualScenarioJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse actual e2e scenario JSON for uid=" + artifactUid
                    + ", relationId=" + rawDataRefId + ", pipelineRunId=" + pipelineRunId, e);
        }

        if (root.path("e2e").path("uid").isMissingNode() || root.path("e2e").path("uid").isNull()) {
            log.warn("Actual e2e_scenario state for uid={}, relationId={}, pipelineRunId={} has no e2e block — skipping fdm-products publish",
                    artifactUid, rawDataRefId, pipelineRunId);
            return;
        }

        E2eV2PublishRequest request = e2ePublishRequestMapper.map(root);
        E2ePublishSource source = E2ePublishSource.forArtifactType(artifactType);
        E2ePublishResponse response;
        try {
            response = e2eProductsClient.upsertE2e(request, rawDataRefId, pipelineRunId, source.name());
        } catch (RuntimeException e) {
            recordPublishFailure(artifactUid, rawDataRefId, pipelineRunId, e);
            throw e;
        }

        cxBiStepRelationsPublisher.publish(root, response != null ? response.getCode() : null,
                artifactUid, rawDataRefId, pipelineRunId);
    }

    private void recordPublishFailure(String artifactUid, Long rawDataRefId, Long pipelineRunId, Exception e) {
        log.error("fdm-products publish failed for uid={}, relationId={}, pipelineRunId={}: {}",
                artifactUid, rawDataRefId, pipelineRunId, e.getMessage());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("uid", artifactUid);
        details.put("relationId", rawDataRefId);
        details.put("pipelineRunId", pipelineRunId);
        details.put("error", e.getMessage());
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception jsonEx) {
            detailsJson = "{}";
        }
        ArtifactNotice notice = new ArtifactNotice(null, null, "publish.failed", "error", "publish",
                rawDataRefId, "e2e_scenario", artifactUid, null, "publish.failed", detailsJson, null, null,
                artifactUid, null);
        artifactNoticeService.saveNoticeInNewTransaction(rawDataRefId, notice);
    }
}
