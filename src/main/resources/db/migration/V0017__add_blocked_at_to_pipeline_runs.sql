ALTER TABLE staging.pipeline_runs
    ADD COLUMN IF NOT EXISTS blocked_at timestamp;

COMMENT ON COLUMN staging.pipeline_runs.blocked_at IS 'When a scan first found this run failed and out of auto-retries, i.e. since when this artifact has not been processed. Reported to the UI and by GET /admin/pipeline-runs/blocked; cleared on retry. NULL for runs that were never blocked.';

-- Runs already blocked before this migration carry no record of how long they have been stuck.
-- Dating them from their failure is the honest approximation: the block starts at most one scan
-- interval after the run fails, so a backlog that has been sitting for days is shown as days old
-- straight away instead of looking freshly blocked on the day of the deploy.
-- The literal 3 is the default of staging.recovery.max-auto-retries; on an environment configured
-- lower this back-fill just misses a few rows, which the next scan then stamps itself.
UPDATE staging.pipeline_runs
   SET blocked_at = COALESCE(completed_at, started_at)
 WHERE artifact_uid IS NOT NULL
   AND status = 'failed'
   AND retry_count >= 3
   AND blocked_at IS NULL;
