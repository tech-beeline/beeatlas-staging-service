-- ============================================================
-- V0011: One active scan per configuration
-- Camunda removal (Этап 1-2): with two staging-service replicas each running their own
-- PipelineTickScheduler, both can observe "no active scan for this config" at the same
-- instant and both try to create one — a classic check-then-act race across processes.
-- A unique partial index turns the loser's INSERT into a DataIntegrityViolationException
-- instead of a duplicate scan, with no coordination/leader-election needed between replicas.
-- ============================================================

CREATE UNIQUE INDEX IF NOT EXISTS idx_pipeline_runs_one_active_scan_per_config
    ON staging.pipeline_runs (configuration_id)
    WHERE artifact_uid IS NULL AND status NOT IN ('completed', 'failed');
