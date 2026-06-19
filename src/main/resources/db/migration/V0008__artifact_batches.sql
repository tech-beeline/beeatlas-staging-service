-- Artifact batch: one row per successful saver completion.
-- A batch groups all *_versions rows created in one pipeline run under a single id.
-- is_current = TRUE means this batch is the latest canonical snapshot for the artifact.
-- All historical batches are kept — the full loading history is preserved.
--
-- Query: "все варианты CJ artifact_uid=E2E-005, последний — эталон"
--   SELECT * FROM staging.artifact_batches
--   WHERE artifact_uid = 'E2E-005' ORDER BY created_at DESC;
--
-- Query: "что входило в батч X (все bi_step_versions)"
--   SELECT bsv.* FROM staging.bi_step_versions bsv WHERE bsv.batch_id = X;

CREATE TABLE staging.artifact_batches (
    id              BIGSERIAL    PRIMARY KEY,
    artifact_uid    VARCHAR(255) NOT NULL,
    artifact_type   VARCHAR(100) NOT NULL,
    run_id          BIGINT       REFERENCES staging.pipeline_runs(id),
    raw_data_ref_id BIGINT       REFERENCES staging.raw_data_refs(id),
    bi_steps_count      INTEGER  NOT NULL DEFAULT 0,
    interfaces_count    INTEGER  NOT NULL DEFAULT 0,
    operations_count    INTEGER  NOT NULL DEFAULT 0,
    is_current      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Only one current batch per artifact at a time
CREATE UNIQUE INDEX idx_artifact_batches_current
    ON staging.artifact_batches(artifact_uid, artifact_type)
    WHERE is_current = TRUE;

CREATE INDEX idx_artifact_batches_artifact ON staging.artifact_batches(artifact_uid, artifact_type);
CREATE INDEX idx_artifact_batches_run_id   ON staging.artifact_batches(run_id);

-- Link each version row to the batch it was created in.
-- Nullable: rows created before this migration don't have a batch_id.
ALTER TABLE staging.bi_step_versions             ADD COLUMN batch_id BIGINT REFERENCES staging.artifact_batches(id);
ALTER TABLE staging.interface_versions           ADD COLUMN batch_id BIGINT REFERENCES staging.artifact_batches(id);
ALTER TABLE staging.operation_versions           ADD COLUMN batch_id BIGINT REFERENCES staging.artifact_batches(id);
ALTER TABLE staging.bi_step_relation_versions    ADD COLUMN batch_id BIGINT REFERENCES staging.artifact_batches(id);
ALTER TABLE staging.operation_relation_versions  ADD COLUMN batch_id BIGINT REFERENCES staging.artifact_batches(id);

CREATE INDEX idx_bi_step_versions_batch       ON staging.bi_step_versions(batch_id);
CREATE INDEX idx_interface_versions_batch     ON staging.interface_versions(batch_id);
CREATE INDEX idx_operation_versions_batch     ON staging.operation_versions(batch_id);
