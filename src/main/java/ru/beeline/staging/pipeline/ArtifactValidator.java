package ru.beeline.staging.pipeline;

import java.util.Map;

/**
 * Strategy for validating raw artifact data downloaded by the Loader stage.
 * One implementation per artifactType is supported architecturally (like ArtifactLoader),
 * but if the upstream source already has only one validation approach for several types,
 * a single implementation can legitimately cover them all.
 */
public interface ArtifactValidator {

    String supportedType();

    /**
     * Validates rawBytes (the decompressed raw payload). Returns process variables to
     * forward (e.g. validationWarnings count), or null/empty if there is nothing to add.
     * Should throw only for fatal, unrecoverable structural problems — soft issues are
     * reported as warnings, mirroring dashboard-main's own behaviour (it never fails the
     * request on validationError, only logs/collects them).
     */
    Map<String, Object> validate(String artifactUid, byte[] rawBytes) throws Exception;
}
