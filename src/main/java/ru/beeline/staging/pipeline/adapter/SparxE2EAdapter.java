package ru.beeline.staging.pipeline.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.sparx.SparxE2ERepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

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
            byte[] content = rawJson.getBytes(StandardCharsets.UTF_8);

            RawDataRef ref = new RawDataRef();
            ref.setArtifactUid(artifactUid);
            ref.setArtifactType(TYPE);
            ref.setSourceId(sourceId);
            ref.setRawContent(content);
            ref.setContentHash(contentHash);
            ref.setSizeBytes((long) content.length);
            ref.setLoadedAt(LocalDateTime.now());
            ref.setUpdatedAt(LocalDateTime.now());
            refId = rawDataRefRepository.save(ref).getId();
            log.info("Stored raw data uid={}, rawDataRefId={}, bytes={}", artifactUid, refId, content.length);
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
