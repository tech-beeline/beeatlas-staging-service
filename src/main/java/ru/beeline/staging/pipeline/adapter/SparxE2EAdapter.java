/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.sparx.SparxE2ERepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SparxE2EAdapter implements ArtifactAdapter {

    public static final String MODULE_CODE = "sparx-e2e-adapter";
    public static final String TYPE        = "e2e-sequence";

    private final SparxE2ERepository   sparxE2ERepository;
    private final RawDataRefRepository rawDataRefRepository;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Downloads the full raw e2e scenario export directly from Sparx EA"; }

    @Override
    public Map<String, Object> load(String artifactUid, String sourceId, Map<String, Object> metadata) throws Exception {
        String rawJson = sparxE2ERepository.fetchScenarioRaw(artifactUid);
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalStateException("Sparx EA returned empty response for uid=" + artifactUid);
        }

        byte[] content = rawJson.getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256(content);

        RawDataRefRepository.UpsertResult result = rawDataRefRepository.upsertByContentHash(
                artifactUid, TYPE, sourceId, "json", content, contentHash, content.length);

        long refId = result.getId();
        if (Boolean.TRUE.equals(result.getInserted())) {
            log.info("Stored raw data uid={}, rawDataRefId={}, bytes={}", artifactUid, refId, content.length);
        } else {
            log.info("Content unchanged for uid={}, reusing rawDataRefId={}", artifactUid, refId);
        }

        return Map.of("rawDataRefId", refId, "contentHash", contentHash);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
