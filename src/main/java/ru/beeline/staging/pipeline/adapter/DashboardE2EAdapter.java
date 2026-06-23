package ru.beeline.staging.pipeline.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dashboard.DashboardClient;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.utils.GzipUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/** Adapter for artifactType=e2e-sequence: downloads the raw scenario JSON from Dashboard. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardE2EAdapter implements ArtifactAdapter {

    public static final String MODULE_CODE = "dashboard-e2e-adapter";
    public static final String TYPE        = "e2e-sequence";

    private final DashboardClient      dashboardClient;
    private final RawDataRefRepository rawDataRefRepository;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public Map<String, Object> load(String artifactUid, String sourceId, Map<String, Object> metadata) throws Exception {
        String rawJson = dashboardClient.getScenarioSequence(artifactUid);
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalStateException("Dashboard returned empty response for uid=" + artifactUid);
        }

        String contentHash = sha256(rawJson.getBytes(StandardCharsets.UTF_8));

        Optional<RawDataRef> existing = rawDataRefRepository
                .findTopByArtifactUidOrderByLoadedAtDesc(artifactUid);

        long refId;
        if (existing.isPresent() && contentHash.equals(existing.get().getContentHash())) {
            RawDataRef ref = existing.get();
            ref.setUpdatedAt(LocalDateTime.now());
            refId = rawDataRefRepository.save(ref).getId();
            log.info("Content unchanged for uid={}, reusing rawDataRefId={}", artifactUid, refId);
        } else {
            byte[] gzipped = GzipUtils.gzip(rawJson.getBytes(StandardCharsets.UTF_8));

            RawDataRef ref = new RawDataRef();
            ref.setArtifactUid(artifactUid);
            ref.setArtifactType(TYPE);
            ref.setSourceId(sourceId);
            ref.setRawContent(gzipped);
            ref.setContentHash(contentHash);
            ref.setSizeBytes((long) gzipped.length);
            ref.setLoadedAt(LocalDateTime.now());
            ref.setUpdatedAt(LocalDateTime.now());
            refId = rawDataRefRepository.save(ref).getId();
            log.info("Stored raw data uid={}, rawDataRefId={}, gzipBytes={}", artifactUid, refId, gzipped.length);
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
