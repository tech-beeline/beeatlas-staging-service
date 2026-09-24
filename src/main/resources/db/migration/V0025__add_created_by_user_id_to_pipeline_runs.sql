ALTER TABLE staging.pipeline_runs
    ADD COLUMN IF NOT EXISTS created_by_user_id integer;

CREATE INDEX IF NOT EXISTS idx_pipeline_runs_created_by_user
    ON staging.pipeline_runs (created_by_user_id, started_at DESC)
    WHERE created_by_user_id IS NOT NULL;
