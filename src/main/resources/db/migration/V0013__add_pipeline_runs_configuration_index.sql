-- ============================================================
-- V0013: Index scan rows by configuration_id
-- PipelineTickScheduler.tick() batches its per-config checks into two queries across all due
-- configs instead of 2×N sequential ones — this index is what makes both of them (active-scan
-- lookup and last-completed-scan-per-config) cheap instead of a sequential scan as configs grow.
-- Partial (artifact_uid IS NULL) — scan rows only, stays small regardless of artifact volume.
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_pipeline_runs_configuration_id_scan
    ON staging.pipeline_runs (configuration_id, completed_at)
    WHERE artifact_uid IS NULL;
