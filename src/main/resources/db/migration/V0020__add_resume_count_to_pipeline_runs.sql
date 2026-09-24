-- ============================================================
-- V0020: Resume-attempt counter for the stall watchdog
-- Camunda removal follow-up: PipelineResumeScheduler re-claims a run whose lease expired, but
-- nothing ever counted those re-claims. A run whose stage starts and never finishes was therefore
-- resumed forever — a new "running" pipeline_stage_logs row every lease period, retry_count stuck
-- at 0 (it is only incremented on the failure path), status stuck non-terminal, and for a scan run
-- the one-active-scan-per-config slot held indefinitely. Observed on FUNC: runs 1571951 and
-- 1595194 looped for 44h with ~500 abandoned stage logs each.
--
-- resume_count counts claims *without progress*: incremented by PipelineRunRepository#claim,
-- reset to 0 whenever a stage actually completes. Once it passes staging.recovery
-- .max-resume-attempts the watchdog forces the run terminal, which is what makes the DoD
-- ("no run stays non-terminal indefinitely without progress") enforceable rather than aspirational.
-- ============================================================

ALTER TABLE staging.pipeline_runs
    ADD COLUMN IF NOT EXISTS resume_count integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN staging.pipeline_runs.resume_count IS
    'Число захватов (claim) прогона без завершения хотя бы одной стадии; сбрасывается при прогрессе';

-- Partial: the watchdog only ever scans non-terminal runs, which are a tiny slice of the table.
CREATE INDEX IF NOT EXISTS idx_pipeline_runs_stalled
    ON staging.pipeline_runs (resume_count)
    WHERE status NOT IN ('completed', 'failed');
