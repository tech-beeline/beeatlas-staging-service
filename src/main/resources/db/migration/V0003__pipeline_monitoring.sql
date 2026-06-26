-- Pipeline monitoring views:
--   pipeline_runs       — one row per trigger: either a pre-adapter scan (artifact_uid IS NULL,
--                         configuration_id set, one "pre-adapter" stage only) or one specific
--                         artifact going through pre-adapter->adapter->validator->transformer->saver
--   pipeline_stage_logs — one row per stage within a run, with full input/output payload

CREATE TABLE staging.pipeline_runs (
    id               BIGSERIAL    PRIMARY KEY,
    artifact_uid     VARCHAR(255),
    artifact_type    VARCHAR(100) NOT NULL,
    configuration_id BIGINT       REFERENCES staging.configurations (id),
    raw_data_ref_id  BIGINT       REFERENCES staging.raw_data_refs (id),
    batch_id         VARCHAR(100),
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending',
    camunda_pid      VARCHAR(255),
    execution_id     VARCHAR(255),
    started_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMP,
    failure_reason   TEXT,
    failed_stage     VARCHAR(50),
    pipeline_definition_id BIGINT REFERENCES staging.pipeline_definitions (id),
    parent_run_id    BIGINT REFERENCES staging.pipeline_runs (id),
    CONSTRAINT pipeline_runs_status_check
        CHECK (status IN ('pending', 'loading', 'validating', 'transforming', 'saving', 'publishing', 'completed', 'failed'))
);

COMMENT ON COLUMN staging.pipeline_runs.artifact_uid IS
    'NULL means this row is a pre-adapter scan attempt, not one artifact — see parent_run_id.';
COMMENT ON COLUMN staging.pipeline_runs.batch_id IS
    'The artifact-pipeline-process instance id of the scan that found this row — shared by the scan row and every artifact it spawned; use parent_run_id to link an artifact back to its specific scan row.';
COMMENT ON COLUMN staging.pipeline_runs.pipeline_definition_id IS
    'FK to staging.pipeline_definitions for this run''s artifactType — avoids duplicating the module sequence into every run row.';
COMMENT ON COLUMN staging.pipeline_runs.parent_run_id IS
    'Self-FK to the scan-attempt row (artifact_uid IS NULL) that found this artifact — query staging.pipeline_runs WHERE parent_run_id = X to see every artifact one pre-adapter scan produced, or read its "pre-adapter" pipeline_stage_logs.output_data.foundArtifactUids for the raw list.';
COMMENT ON COLUMN staging.pipeline_runs.execution_id IS
    'Camunda execution id of this artifact''s own multi-instance loop iteration — camunda_pid alone is not enough to target retries since one process instance now runs many artifacts (multi-instance subprocess).';

CREATE INDEX idx_pipeline_runs_artifact ON staging.pipeline_runs (artifact_uid, artifact_type);
CREATE INDEX idx_pipeline_runs_status   ON staging.pipeline_runs (status);
CREATE INDEX idx_pipeline_runs_started  ON staging.pipeline_runs (started_at DESC);
CREATE INDEX idx_pipeline_runs_batch_id ON staging.pipeline_runs (batch_id);
CREATE INDEX idx_pipeline_runs_parent_run_id ON staging.pipeline_runs (parent_run_id);

CREATE TABLE staging.pipeline_stage_logs (
    id             BIGSERIAL   PRIMARY KEY,
    run_id         BIGINT      NOT NULL REFERENCES staging.pipeline_runs (id),
    scan_run_id    BIGINT      NOT NULL REFERENCES staging.pipeline_runs (id),
    stage_name     VARCHAR(50) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'running',
    input_data     TEXT,
    output_data    TEXT,
    summary_json   JSONB,
    started_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMP,
    failure_reason TEXT,
    CONSTRAINT pipeline_stage_logs_status_check
        CHECK (status IN ('running', 'completed', 'failed', 'skipped'))
);

COMMENT ON COLUMN staging.pipeline_stage_logs.input_data  IS 'Just the identifier this stage worked on (e.g. rawDataRefId, artifactUid) — plain text, not JSON. The rest is already on pipeline_runs.';
COMMENT ON COLUMN staging.pipeline_stage_logs.output_data IS 'Just the identifier/result this stage produced — plain text, not JSON. For a scan-attempt''s "pre-adapter" stage: comma-separated foundArtifactUids.';
COMMENT ON COLUMN staging.pipeline_stage_logs.summary_json IS 'Lightweight scalar metrics only (subset of output_data) — kept for quick dashboards.';
COMMENT ON COLUMN staging.pipeline_stage_logs.scan_run_id IS
    'Denormalized: the pre-adapter scan (pipeline_runs row with artifact_uid IS NULL) this stage ultimately belongs to — equal to run_id for the scan''s own "pre-adapter" stage, or to that run''s parent_run_id for an artifact''s stages. Lets you filter every stage of every artifact one scan produced without joining pipeline_runs twice; combine with run_id (or pipeline_runs.artifact_uid) to narrow to one specific artifact within that scan.';

CREATE INDEX idx_pipeline_stage_logs_run_id      ON staging.pipeline_stage_logs (run_id);
CREATE INDEX idx_pipeline_stage_logs_scan_run_id ON staging.pipeline_stage_logs (scan_run_id);
