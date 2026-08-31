/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import ru.beeline.staging.product.ProductServiceClient;
import ru.beeline.staging.product.dto.ProductSummary;
import ru.beeline.staging.repository.RawDataRefRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StructurizrSequenceAdapter implements ArtifactAdapter {

    public static final String MODULE_CODE = "structurizr-sequence-adapter";
    public static final String TYPE        = "structurizr-sequence";

    private final ProductServiceClient productServiceClient;
    private final RestTemplate         restTemplate;
    private final RawDataRefRepository rawDataRefRepository;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Downloads a product's Structurizr workspace export (structurizrApiUrl + /json)"; }

    @Override
    public Map<String, Object> load(String artifactUid, String sourceId, Map<String, Object> metadata) throws Exception {
        ProductSummary product = productServiceClient.getProductInfo(artifactUid)
                .orElseThrow(() -> new IllegalStateException("Product not found in fdm-products: alias=" + artifactUid));

        String structurizrApiUrl = product.getStructurizrApiUrl();
        if (structurizrApiUrl == null || structurizrApiUrl.isBlank()) {
            throw new IllegalStateException("Product has no structurizrApiUrl: alias=" + artifactUid);
        }

        String jsonUrl = (structurizrApiUrl.endsWith("/") ? structurizrApiUrl.substring(0, structurizrApiUrl.length() - 1) : structurizrApiUrl) + "/json";
        log.info("Fetching Structurizr workspace export: alias={} url={}", artifactUid, jsonUrl);
        String rawJson;
        try {
            rawJson = restTemplate.getForObject(jsonUrl, String.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch Structurizr workspace: alias=" + artifactUid + " url=" + jsonUrl + " — " + e.getMessage(), e);
        }
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalStateException("Structurizr returned empty workspace export for alias=" + artifactUid + " url=" + jsonUrl);
        }

        byte[] content = rawJson.getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256(content);

        RawDataRefRepository.UpsertResult result = rawDataRefRepository.upsertByContentHash(
                artifactUid, TYPE, sourceId, "json", content, contentHash, content.length);
        long refId = result.getId();
        if (Boolean.TRUE.equals(result.getInserted())) {
            log.info("Stored raw workspace export alias={}, rawDataRefId={}, bytes={}", artifactUid, refId, content.length);
        } else {
            log.info("Content unchanged for alias={}, reusing rawDataRefId={}", artifactUid, refId);
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
