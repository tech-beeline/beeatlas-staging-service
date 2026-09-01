-- ============================================================
-- V0015: Deduplicate raw_data_contexts / artifact_notices, then enforce it
-- ============================================================
-- Root cause: RawDataContextService.save() / ArtifactNoticeService.saveNotices()
-- did a blind INSERT with no existence check, so every repeat pass over
-- unchanged content — or a losing racer in the same check-then-act window
-- V0006 already closed for artifact_batches, one stage later in the chain —
-- inserted a fresh duplicate row every time. The app-code fix (find-or-create
-- in both services, see StructurizrSequenceAdapter/RawDataContextService/
-- ArtifactNoticeService) stops new duplicates from accumulating; this
-- migration cleans up what already piled up. As of 2026-08-31,
-- raw_data_contexts had ~8.2M rows for only ~349K distinct
-- (raw_data_ref_id, position) pairs (~96% removable).
--
-- 14 canonical version/relation tables carry their own raw_data_context_id
-- and match_notice_id FKs straight to the rows being collapsed here.
--
-- IMPORTANT — this is the second version of this migration. The first one
-- ran a DELETE FROM raw_data_contexts for 13+ hours on dev without finishing.
-- Root cause: Postgres does NOT auto-index foreign key columns. Deleting a
-- row that other tables reference requires an RI (referential integrity)
-- trigger check against every referencing table for every deleted row — with
-- no index on the referencing column, that's a full sequential scan of the
-- child table PER DELETED PARENT ROW. With ~7.8M rows being deleted from
-- raw_data_contexts and 13 referencing tables missing an index on
-- raw_data_context_id (only artifact_notices and
-- metric_query_template_versions had one), that's ~7.8M sequential scans
-- across those tables — mathematically hopeless, not just slow. Step 0 below
-- adds the missing indexes FIRST (fast — these are small tables) so the RI
-- checks during the DELETEs become index lookups instead.
--
-- Runs as one transaction. Even with the missing indexes fixed, this is a
-- large one-time cleanup — run it during a low-traffic window. Recommend a
-- manual VACUUM (ANALYZE) on raw_data_contexts and artifact_notices
-- afterwards (can't run inside this migration's transaction).
-- ============================================================

-- ------------------------------------------------------------
-- 0. Indexes needed for the DELETEs below to actually be fast — Postgres
--    does not create these automatically for FK columns. Cheap: all 14
--    tables here are small (thousands to tens of thousands of rows).
-- ------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_bi_step_versions_raw_data_context_id             ON staging.bi_step_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_tech_capability_versions_raw_data_context_id     ON staging.tech_capability_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_product_versions_raw_data_context_id            ON staging.product_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_container_versions_raw_data_context_id          ON staging.container_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_interface_versions_raw_data_context_id          ON staging.interface_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_operation_versions_raw_data_context_id          ON staging.operation_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_sequence_versions_raw_data_context_id           ON staging.sequence_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_e2e_scenario_versions_raw_data_context_id       ON staging.e2e_scenario_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_cj_versions_raw_data_context_id                 ON staging.cj_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_cj_step_versions_raw_data_context_id            ON staging.cj_step_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_bi_step_relation_versions_raw_data_context_id   ON staging.bi_step_relation_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_operation_relation_versions_raw_data_context_id ON staging.operation_relation_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_sequence_relation_versions_raw_data_context_id  ON staging.sequence_relation_versions (raw_data_context_id);

-- match_notice_id: only these 3 relation tables were missing it (the other
-- 11 already had idx_*_match_notice_id from V0003/V0009).
CREATE INDEX IF NOT EXISTS idx_bi_step_relation_versions_match_notice_id       ON staging.bi_step_relation_versions (match_notice_id);
CREATE INDEX IF NOT EXISTS idx_operation_relation_versions_match_notice_id     ON staging.operation_relation_versions (match_notice_id);
CREATE INDEX IF NOT EXISTS idx_sequence_relation_versions_match_notice_id      ON staging.sequence_relation_versions (match_notice_id);

-- ------------------------------------------------------------
-- 1. Collapse raw_data_contexts duplicates: keep the lowest id per
--    (raw_data_ref_id, position).
-- ------------------------------------------------------------
CREATE TEMP TABLE context_survivor ON COMMIT DROP AS
SELECT raw_data_ref_id, position, min(id) AS keep_id
FROM staging.raw_data_contexts
GROUP BY raw_data_ref_id, position;
ANALYZE context_survivor;

CREATE TEMP TABLE context_remap ON COMMIT DROP AS
SELECT c.id AS old_id, s.keep_id
FROM staging.raw_data_contexts c
JOIN context_survivor s
  ON s.raw_data_ref_id = c.raw_data_ref_id AND s.position = c.position
WHERE c.id <> s.keep_id;
ANALYZE context_remap;

-- 1b. Repoint every table that holds a raw_data_context_id off the losers.
UPDATE staging.artifact_notices             t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.bi_step_versions             t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.tech_capability_versions     t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.product_versions             t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.container_versions           t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.interface_versions           t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.operation_versions           t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.sequence_versions            t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.e2e_scenario_versions        t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.cj_versions                  t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.cj_step_versions             t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.bi_step_relation_versions    t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.operation_relation_versions  t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.sequence_relation_versions   t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;
UPDATE staging.metric_query_template_versions t SET raw_data_context_id = r.keep_id FROM context_remap r WHERE t.raw_data_context_id = r.old_id;

DELETE FROM staging.raw_data_contexts c
USING context_remap r
WHERE c.id = r.old_id;

-- ------------------------------------------------------------
-- 2. Collapse artifact_notices duplicates that step 1's remap just created
--    (two notices that used to point at different-but-identical context
--    rows now point at the same surviving row).
-- ------------------------------------------------------------
CREATE TEMP TABLE notice_survivor ON COMMIT DROP AS
SELECT raw_data_context_id, notice_type_id, details, min(id) AS keep_id
FROM staging.artifact_notices
GROUP BY raw_data_context_id, notice_type_id, details;
ANALYZE notice_survivor;

CREATE TEMP TABLE notice_remap ON COMMIT DROP AS
SELECT an.id AS old_id, s.keep_id
FROM staging.artifact_notices an
JOIN notice_survivor s
  ON s.raw_data_context_id = an.raw_data_context_id
 AND s.notice_type_id = an.notice_type_id
 AND s.details IS NOT DISTINCT FROM an.details
WHERE an.id <> s.keep_id;
ANALYZE notice_remap;

-- 2b. Repoint every table that holds a match_notice_id off the losers.
UPDATE staging.bi_step_versions             t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.tech_capability_versions     t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.product_versions             t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.container_versions           t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.interface_versions           t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.operation_versions           t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.sequence_versions            t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.e2e_scenario_versions        t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.cj_versions                  t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.cj_step_versions             t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.bi_step_relation_versions    t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.operation_relation_versions  t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.sequence_relation_versions   t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;
UPDATE staging.metric_query_template_versions t SET match_notice_id = r.keep_id FROM notice_remap r WHERE t.match_notice_id = r.old_id;

DELETE FROM staging.artifact_notices an
USING notice_remap r
WHERE an.id = r.old_id;

-- ------------------------------------------------------------
-- 3. Now that duplicates are gone, enforce it going forward — matches the
--    find-or-create logic in RawDataContextService/ArtifactNoticeService.
--    details is hashed in the notices index: it's a free-form text column,
--    long values would otherwise risk exceeding btree's per-entry size limit.
--    IF NOT EXISTS: makes the whole migration safely re-runnable.
-- ------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS idx_raw_data_contexts_ref_position_unique
    ON staging.raw_data_contexts (raw_data_ref_id, position);

CREATE UNIQUE INDEX IF NOT EXISTS idx_artifact_notices_context_type_details_unique
    ON staging.artifact_notices (raw_data_context_id, notice_type_id, md5(coalesce(details, '')));
