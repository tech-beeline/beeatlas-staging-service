-- ============================================================
-- V0022: Pause context of a human-in-the-loop run
-- SFDM-4122: GET /api/v1/pipeline-runs/{runId}/status returns the transformer result as the
-- "result" block for awaiting_review/reviewing/completed (REQ-staging-009 FR-009-08,
-- REQ-staging-010 FR-010-12). The column is added ahead of the HITL engine (SFDM-4070), so the
-- endpoint serves the draft as soon as the engine starts writing it.
-- ============================================================

ALTER TABLE staging.pipeline_runs
    ADD COLUMN IF NOT EXISTS draft_json text;

COMMENT ON COLUMN staging.pipeline_runs.draft_json IS
    'Результат transformer (контекст паузы HITL): {usecase, mapped[], unmapped[]}; источник блока result в GET /pipeline-runs/{runId}/status';
