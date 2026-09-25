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
import ru.beeline.staging.product.dto.e2e.E2eV2PublishRequest;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2eProductsPublisher {

    private static final String PUBLISH_FAILED_CODE = "publish.failed";
    private static final String PUBLISH_SKIPPED_CODE = "publish.skipped";

    private final ActualE2eScenarioRepository actualE2eScenarioRepository;
    private final E2eV2PublishRequestMapper e2ePublishRequestMapper;
    private final E2eProductsClient e2eProductsClient;
    private final CxBiStepRelationsPublisher cxBiStepRelationsPublisher;
    private final ArtifactNoticeService artifactNoticeService;
    private final ObjectMapper objectMapper;

    public void publish(String artifactUid, String artifactType, Long rawDataRefId, Long pipelineRunId) {
        String actualScenarioJson = actualE2eScenarioRepository.fetchActualScenarioRaw(artifactUid, artifactType);
        if (actualScenarioJson == null) {
            recordPublishSkipped(artifactUid, artifactType, rawDataRefId, pipelineRunId,
                    "Не найдено актуальное состояние e2e_scenario для артефакта");
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
            recordPublishSkipped(artifactUid, artifactType, rawDataRefId, pipelineRunId,
                    "В актуальном состоянии e2e_scenario отсутствует блок e2e");
            return;
        }

        E2eV2PublishRequest request = e2ePublishRequestMapper.map(root);
        E2ePublishSource source = E2ePublishSource.forArtifactType(artifactType);
        try {
            e2eProductsClient.upsertE2e(request, rawDataRefId, pipelineRunId, source.name());
        } catch (RuntimeException e) {
            recordPublishFailure(artifactUid, rawDataRefId, pipelineRunId, e);
            throw e;
        }

        cxBiStepRelationsPublisher.publish(root, artifactUid, rawDataRefId, pipelineRunId);
    }

    private void recordPublishFailure(String artifactUid, Long rawDataRefId, Long pipelineRunId, Exception e) {
        log.error("fdm-products publish failed for uid={}, relationId={}, pipelineRunId={}: {}",
                artifactUid, rawDataRefId, pipelineRunId, e.getMessage());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("uid", artifactUid);
        details.put("relationId", rawDataRefId);
        details.put("pipelineRunId", pipelineRunId);
        details.put("error", e.getMessage());
        saveNotice(PUBLISH_FAILED_CODE, "error", artifactUid, rawDataRefId, PUBLISH_FAILED_CODE, details);
    }

    private void recordPublishSkipped(String artifactUid, String artifactType, Long rawDataRefId, Long pipelineRunId,
            String reason) {
        log.warn("{} for uid={}, artifactType={}, relationId={}, pipelineRunId={} — skipping fdm-products publish",
                reason, artifactUid, artifactType, rawDataRefId, pipelineRunId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("uid", artifactUid);
        details.put("artifactType", artifactType);
        details.put("relationId", rawDataRefId);
        details.put("pipelineRunId", pipelineRunId);
        details.put("reason", reason);
        saveNotice(PUBLISH_SKIPPED_CODE, "warning", artifactUid, rawDataRefId, reason, details);
    }

    private void saveNotice(String code, String level, String artifactUid, Long rawDataRefId, String message,
            Map<String, Object> details) {
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception jsonEx) {
            detailsJson = "{}";
        }
        ArtifactNotice notice = new ArtifactNotice(null, null, code, level, "publish",
                rawDataRefId, "e2e_scenario", artifactUid, null, message, detailsJson, null, null,
                artifactUid, null);
        artifactNoticeService.saveNoticeInNewTransaction(rawDataRefId, notice);
    }
}
