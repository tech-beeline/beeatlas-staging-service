package ru.beeline.staging.pipeline.e2e;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dashboard.DashboardClient;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.pipeline.ArtifactLoader;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.storage.S3StorageService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Loader for artifactType=e2e-sequence. Uses dashboard-main's existing
 * /api/v4/e2e/scenarios/{uid}/sequence endpoint as a proxy in front of Sparx EA,
 * instead of re-implementing the call-tree extraction ourselves (see ADR-001).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardE2ELoader implements ArtifactLoader {

    public static final String TYPE = "e2e-sequence";

    private final DashboardClient      dashboardClient;
    private final S3StorageService     s3StorageService;
    private final RawDataRefRepository rawDataRefRepository;

    @Override
    public String supportedType() { return TYPE; }

    @Override
    public Map<String, Object> load(String artifactUid, String sourceId, Map<String, Object> metadata) throws Exception {
        String rawJson = dashboardClient.getScenarioSequence(artifactUid);
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalStateException("Dashboard returned empty response for uid=" + artifactUid);
        }
        byte[] rawBytes = rawJson.getBytes(StandardCharsets.UTF_8);
        String contentHash = S3StorageService.sha256(rawBytes);

        Optional<RawDataRef> existing = rawDataRefRepository
                .findTopByArtifactUidOrderByLoadedAtDesc(artifactUid);

        long refId;
        if (existing.isPresent() && contentHash.equals(existing.get().getContentHash())) {
            RawDataRef ref = existing.get();
            ref.setUpdatedAt(LocalDateTime.now());
            refId = rawDataRefRepository.save(ref).getId();
            log.info("Content unchanged for uid={}, reusing rawDataRefId={}", artifactUid, refId);
        } else {
            String s3Key = String.format("raw/%s/%s/%s.gz", TYPE, artifactUid, contentHash);
            s3StorageService.putGzip(rawBytes, s3Key);

            RawDataRef ref = new RawDataRef();
            ref.setArtifactUid(artifactUid);
            ref.setArtifactType(TYPE);
            ref.setSourceId(sourceId);
            ref.setS3Bucket(s3StorageService.getBucket());
            ref.setS3Key(s3Key);
            ref.setContentHash(contentHash);
            ref.setSizeBytes((long) rawBytes.length);
            ref.setLoadedAt(LocalDateTime.now());
            ref.setUpdatedAt(LocalDateTime.now());
            refId = rawDataRefRepository.save(ref).getId();
            log.info("Stored raw data uid={}, rawDataRefId={}, s3Key={}", artifactUid, refId, s3Key);
        }

        return Map.of("rawDataRefId", refId, "contentHash", contentHash);
    }
}
