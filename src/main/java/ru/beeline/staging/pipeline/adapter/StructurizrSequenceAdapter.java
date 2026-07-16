package ru.beeline.staging.pipeline.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.product.ProductServiceClient;
import ru.beeline.staging.product.dto.ProductSummary;
import ru.beeline.staging.repository.RawDataRefRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

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

        String contentHash = sha256(rawJson.getBytes(StandardCharsets.UTF_8));

        Optional<RawDataRef> existing = rawDataRefRepository
                .findTopByArtifactUidOrderByLoadedAtDesc(artifactUid);

        long refId;
        if (existing.isPresent() && contentHash.equals(existing.get().getContentHash())) {
            RawDataRef ref = existing.get();
            ref.setUpdatedAt(LocalDateTime.now());
            refId = rawDataRefRepository.save(ref).getId();
            log.info("Content unchanged for alias={}, reusing rawDataRefId={}", artifactUid, refId);
        } else {
            byte[] content = rawJson.getBytes(StandardCharsets.UTF_8);

            RawDataRef ref = new RawDataRef();
            ref.setArtifactUid(artifactUid);
            ref.setArtifactType(TYPE);
            ref.setSourceId(sourceId);
            ref.setFormat("json");
            ref.setRawContent(content);
            ref.setContentHash(contentHash);
            ref.setSizeBytes((long) content.length);
            ref.setLoadedAt(LocalDateTime.now());
            ref.setUpdatedAt(LocalDateTime.now());
            refId = rawDataRefRepository.save(ref).getId();
            log.info("Stored raw workspace export alias={}, rawDataRefId={}, bytes={}", artifactUid, refId, content.length);
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
