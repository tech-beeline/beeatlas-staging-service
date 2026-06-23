package ru.beeline.staging.pipeline.validator;

import java.util.Map;

/**
 * Validates raw artifact data downloaded by the adapter stage. Selected per
 * configuration by moduleCode — the same validator implementation can legitimately be
 * reused across several similar entity types if their raw shape matches.
 */
public interface ArtifactValidator {

    String moduleCode();

    /**
     * Validates rawContent (the decompressed raw payload). Returns process variables to
     * forward (e.g. validationWarnings count), or null/empty if there is nothing to add.
     * Should throw only for fatal, unrecoverable structural problems — soft issues are
     * reported as warnings.
     */
    Map<String, Object> validate(String artifactUid, String rawContent) throws Exception;
}
