package ru.beeline.staging.pipeline;

/**
 * Extension seam for pushing a persisted canonical snapshot out to other microservices
 * (e.g. cx-backend's BI/CJ library).
 */
public interface CanonicalModelPublisher {

    void publish(String artifactType, String artifactUid, CanonicalSnapshot snapshot,
                CanonicalModelSaverService.SaveResult result);
}
