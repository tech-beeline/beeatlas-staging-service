package ru.beeline.staging.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fallback {@link CanonicalModelPublisher} used only when the real cx-backend integration
 * is explicitly disabled (staging.cx-backend.enabled=false) — e.g. for local runs without
 * a cx-backend instance available.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "staging.cx-backend.enabled", havingValue = "false")
public class NoopCanonicalModelPublisher implements CanonicalModelPublisher {

    @Override
    public void publish(String artifactType, String artifactUid, CanonicalSnapshot snapshot,
                        CanonicalModelSaverService.SaveResult result) {
        log.debug("CanonicalModelPublisher no-op (cx-backend integration disabled): type={}, uid={}, result={}",
                artifactType, artifactUid, result);
    }
}
