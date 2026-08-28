-- ============================================================
-- V0012: Crash-resume leasing for pipeline_runs
-- Camunda removal (Этап 4): with Camunda gone there is no persisted execution engine to resume
-- a run's remaining stages after a crash — pipeline_runs/pipeline_stage_logs already record what
-- happened, this adds who currently owns a run and until when, so an abandoned run (owner's lease
-- expired) is trivially detectable and re-claimable by any instance, including the one that just
-- restarted. Safe across the 2 prod replicas: the claim is a plain UPDATE ... WHERE, which Postgres
-- serializes at the row level under READ COMMITTED — no separate lock table needed.
-- ============================================================

ALTER TABLE staging.pipeline_runs
    ADD COLUMN IF NOT EXISTS owner_id varchar(100),
    ADD COLUMN IF NOT EXISTS lease_expires_at timestamp,
    ADD COLUMN IF NOT EXISTS retry_count integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN staging.pipeline_runs.owner_id IS 'Instance currently processing this run (hostname + a random uuid) — for crash-resume claiming and debugging which replica held it';
COMMENT ON COLUMN staging.pipeline_runs.lease_expires_at IS 'Claim expiry — an expired lease makes the run reclaimable by any instance, including the one that just restarted';
COMMENT ON COLUMN staging.pipeline_runs.retry_count IS 'Persisted retry counter for failed runs, replaces the old in-memory (restart-losing) StuckProcessMonitor counter';

CREATE INDEX IF NOT EXISTS idx_pipeline_runs_status_lease
    ON staging.pipeline_runs (status, lease_expires_at);
