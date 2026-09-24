/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.client.DocumentServiceClient;
import ru.beeline.staging.pipeline.StageContext;
import ru.beeline.staging.repository.RawDataRefRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlantUmlE2EAdapter implements ArtifactAdapter {

    public static final String MODULE_CODE = "e2e-plantuml-adapter";
    public static final String TYPE = "e2e-plantuml";

    private static final String DEFAULT_SOURCE_CODE = "beeatlas-ui";
    private static final int MAX_PLANT_UML_BYTES = 512 * 1024;

    private final DocumentServiceClient documentServiceClient;
    private final RawDataRefRepository rawDataRefRepository;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Accepts the PlantUML e2e diagram from the run payload or document-service"; }

    @Override
    public Map<String, Object> load(String artifactUid, String sourceId, StageContext context) throws Exception {
        String plantUml = resolveText(artifactUid, context);

        byte[] content = plantUml.getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_PLANT_UML_BYTES) {
            throw new IllegalStateException("PlantUML text exceeds " + MAX_PLANT_UML_BYTES
                    + " bytes for uid=" + artifactUid + ": " + content.length);
        }
        String contentHash = sha256(content);

        RawDataRefRepository.UpsertResult result = rawDataRefRepository.upsertByContentHash(
                artifactUid, TYPE, resolveSourceCode(sourceId, context), "text", content, contentHash, content.length);

        long refId = result.getId();
        boolean inserted = Boolean.TRUE.equals(result.getInserted());
        if (inserted) {
            log.info("Stored PlantUML e2e text uid={}, rawDataRefId={}, bytes={}", artifactUid, refId, content.length);
        } else {
            log.info("PlantUML e2e text unchanged for uid={}, reusing rawDataRefId={}", artifactUid, refId);
        }

        return Map.of("rawDataRefId", refId, "contentHash", contentHash, "skipped", !inserted);
    }

    private String resolveText(String artifactUid, StageContext context) {
        JsonNode payload = context.payloadOrMissing();
        String plantUml = context.payloadText("plantUml");
        boolean hasPlantUml = plantUml != null && !plantUml.isBlank();
        boolean hasDocId = payload.hasNonNull("docId");

        if (hasPlantUml && hasDocId) {
            throw new IllegalStateException("Both plantUml and docId supplied for uid=" + artifactUid);
        }
        if (hasPlantUml) {
            return plantUml;
        }
        if (!hasDocId) {
            throw new IllegalStateException("Neither plantUml nor docId supplied for uid=" + artifactUid);
        }
        return documentServiceClient.fetchContent(payload.get("docId").asLong());
    }

    private String resolveSourceCode(String sourceId, StageContext context) {
        if (context.sourceCode() != null && !context.sourceCode().isBlank()) {
            return context.sourceCode();
        }
        return sourceId != null && !sourceId.isBlank() ? sourceId : DEFAULT_SOURCE_CODE;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
