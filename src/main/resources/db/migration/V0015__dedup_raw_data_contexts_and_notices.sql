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
-- raw_data_contexts had 8 188 520 rows for only 349 339 distinct
-- (raw_data_ref_id, position) pairs (~96% removable).
--
-- 14 canonical version/relation tables carry their own raw_data_context_id
-- and match_notice_id FKs straight to the rows being collapsed here
-- (bi_step_versions, tech_capability_versions, product_versions,
-- container_versions, interface_versions, operation_versions,
-- sequence_versions, e2e_scenario_versions, cj_versions, cj_step_versions,
-- bi_step_relation_versions, operation_relation_versions,
-- sequence_relation_versions, metric_query_template_versions) — every one of
-- them gets repointed to the surviving row before anything is deleted, or
-- the DELETEs below fail with a foreign key violation (same class of error
-- as fk_source_artifacts_last_run_id / fk_artifact_batches_run_id during the
-- 2026-08-31 pipeline_runs cleanup).
--
-- Runs as one transaction. On a large existing dataset this can take a while
-- (deletes ~96% of raw_data_contexts and a smaller share of artifact_notices)
-- — run it during a low-traffic window rather than assuming it finishes
-- instantly at app startup. Recommend a manual VACUUM (ANALYZE) on both
-- tables afterwards (can't run inside this migration's transaction).
-- ============================================================

-- ------------------------------------------------------------
-- 1. Collapse raw_data_contexts duplicates: keep the lowest id per
--    (raw_data_ref_id, position).
-- ------------------------------------------------------------
CREATE TEMP TABLE context_survivor ON COMMIT DROP AS
SELECT raw_data_ref_id, position, min(id) AS keep_id
FROM staging.raw_data_contexts
GROUP BY raw_data_ref_id, position;

CREATE TEMP TABLE context_remap ON COMMIT DROP AS
SELECT c.id AS old_id, s.keep_id
FROM staging.raw_data_contexts c
JOIN context_survivor s
  ON s.raw_data_ref_id = c.raw_data_ref_id AND s.position = c.position
WHERE c.id <> s.keep_id;

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

CREATE TEMP TABLE notice_remap ON COMMIT DROP AS
SELECT an.id AS old_id, s.keep_id
FROM staging.artifact_notices an
JOIN notice_survivor s
  ON s.raw_data_context_id = an.raw_data_context_id
 AND s.notice_type_id = an.notice_type_id
 AND s.details IS NOT DISTINCT FROM an.details
WHERE an.id <> s.keep_id;

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
-- ------------------------------------------------------------
CREATE UNIQUE INDEX idx_raw_data_contexts_ref_position_unique
    ON staging.raw_data_contexts (raw_data_ref_id, position);

CREATE UNIQUE INDEX idx_artifact_notices_context_type_details_unique
    ON staging.artifact_notices (raw_data_context_id, notice_type_id, md5(coalesce(details, '')));
