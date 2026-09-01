ALTER TABLE staging.pipeline_runs
    ADD COLUMN IF NOT EXISTS execution_started_at timestamp;

COMMENT ON COLUMN staging.pipeline_runs.execution_started_at IS 'When this run''s first stage actually started executing (set once, on the first startStage() call) — unlike started_at, excludes time spent queued waiting for a free thread';
