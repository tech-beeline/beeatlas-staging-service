package ru.beeline.staging.pipeline.preadapter;

import ru.beeline.staging.domain.Configuration;

/**
 * Scans a source for artifacts described by a {@link Configuration} and starts one
 * artifact-pipeline-process per item found. Selected per configuration by moduleCode
 * (see ru.beeline.staging.service.ModuleResolver) — not tied to any artifactType.
 */
public interface ArtifactPreAdapter {

    String moduleCode();

    /** Human-readable description for the module catalog (staging.module_catalog). */
    String description();

    /** Returns the number of items found/started. */
    int scanAndPublish(Configuration config, String batchId);
}
