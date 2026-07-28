-- ============================================================
-- V0006: Enforce a single is_current=TRUE artifact_batch per artifact
-- ============================================================
-- Bug: ArtifactBatchRepository.clearCurrentFlag() + INSERT (new batch,
-- current=true) are two separate statements inside one @Transactional
-- method (PipelineRunService.createBatch). Under READ COMMITTED, two
-- concurrent createBatch() calls for the SAME artifact_uid/artifact_type
-- (e.g. a manually-triggered scan racing the scheduled tick) can interleave:
-- Tx1's UPDATE clears the old row and commits a new current=true row; Tx2's
-- UPDATE was blocked on the old row, wakes up after Tx1 commits, sees the
-- old row is already current=false (nothing to do) but never re-scans for
-- Tx1's newly-inserted row (it didn't exist when Tx2's UPDATE started) —
-- Tx2 then inserts its own current=true row. Result: two current=true rows
-- for the same artifact.
--
-- BEFORE applying this migration, clean up existing duplicates manually —
-- the unique index creation will fail if any (artifact_uid, artifact_type)
-- pair currently has more than one is_current=TRUE row. Example cleanup,
-- keeping the most recently created row per artifact:
--
--   WITH ranked AS (
--     SELECT id, ROW_NUMBER() OVER (
--       PARTITION BY artifact_uid, artifact_type ORDER BY created_at DESC, id DESC
--     ) AS rn
--     FROM staging.artifact_batches
--     WHERE is_current = TRUE
--   )
--   UPDATE staging.artifact_batches
--   SET is_current = FALSE
--   WHERE id IN (SELECT id FROM ranked WHERE rn > 1);
-- ============================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_artifact_batches_current
    ON staging.artifact_batches (artifact_uid, artifact_type)
    WHERE is_current = TRUE;
