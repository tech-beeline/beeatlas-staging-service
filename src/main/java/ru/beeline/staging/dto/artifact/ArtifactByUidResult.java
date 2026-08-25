package ru.beeline.staging.dto.artifact;

import java.time.LocalDateTime;

/**
 * Result of GET /api/v1/artifacts/{artifactType}/{artifactUid} — identity record from
 * staging.source_artifacts enriched with source context (source_systems.code/name) and
 * artifact type name (source_artifact_types.name).
 *
 * <p>Ported from documentation/staging-service/api/rest/GET__api_v1_artifacts__artifactType___artifactUid_.md
 * — keep in sync with that spec.
 */
public record ArtifactByUidResult(
        Long id,
        String extUid,
        String name,
        String status,
        Long artifactTypeId,
        String artifactTypeName,
        String sourceCode,
        String sourceName,
        Long lastRunId,
        Long lastLoadedRefId,
        Long lastSeenScanRunId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}