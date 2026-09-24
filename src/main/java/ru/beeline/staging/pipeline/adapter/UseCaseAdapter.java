/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.StageContext;
import ru.beeline.staging.repository.RawDataRefRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UseCaseAdapter implements ArtifactAdapter {

    public static final String MODULE_CODE = "usecase-adapter";
    public static final String TYPE = "usecase";

    private static final String DEFAULT_SOURCE_CODE = "beeatlas-ui";
    private static final int MAX_PLANT_UML_BYTES = 512 * 1024;

    private final RawDataRefRepository rawDataRefRepository;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Accepts the UseCase PlantUML sequence diagram from the run payload"; }

    @Override
    public Map<String, Object> load(String artifactUid, String sourceId, StageContext context) {
        String plantUml = context.payloadText("plantUml");
        if (plantUml == null || plantUml.isBlank()) {
            throw new IllegalStateException("payload.plantUml is missing for uid=" + artifactUid);
        }

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
        log.info("UseCase PlantUML uid={} rawDataRefId={} {}", artifactUid, refId, inserted ? "stored" : "unchanged");
        return Map.of("rawDataRefId", refId, "contentHash", contentHash, "skipped", !inserted);
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
