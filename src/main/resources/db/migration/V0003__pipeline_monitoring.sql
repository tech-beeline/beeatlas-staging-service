-- Pipeline monitoring views #2 and #3:
--   pipeline_runs       — one row per artifact x trigger, overall status, batch grouping
--   pipeline_stage_logs — one row per stage within a run, with full input/output payload

CREATE TABLE staging.pipeline_runs (
    id               BIGSERIAL    PRIMARY KEY,
    artifact_uid     VARCHAR(255) NOT NULL,
    artifact_type    VARCHAR(100) NOT NULL,
    configuration_id BIGINT       REFERENCES staging.configurations (id),
    raw_data_ref_id  BIGINT       REFERENCES staging.raw_data_refs (id),
    batch_id         VARCHAR(100),
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending',
    camunda_pid      VARCHAR(255),
    started_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMP,
    failure_reason   TEXT,
    failed_stage     VARCHAR(50),
    modules_sequence JSONB,
    CONSTRAINT pipeline_runs_status_check
        CHECK (status IN ('pending', 'loading', 'validating', 'transforming', 'saving', 'publishing', 'completed', 'failed'))
);

COMMENT ON COLUMN staging.pipeline_runs.batch_id IS
    'Groups all pipeline_runs spawned by one preAdapter scan (the pre-adapter-process instance id that found this item).';
COMMENT ON COLUMN staging.pipeline_runs.modules_sequence IS
    'JSON array of moduleCodes planned for this run, in execution order, snapshotted from PipelineDefinition at creation time.';

CREATE INDEX idx_pipeline_runs_artifact ON staging.pipeline_runs (artifact_uid, artifact_type);
CREATE INDEX idx_pipeline_runs_status   ON staging.pipeline_runs (status);
CREATE INDEX idx_pipeline_runs_started  ON staging.pipeline_runs (started_at DESC);
CREATE INDEX idx_pipeline_runs_batch_id ON staging.pipeline_runs (batch_id);

CREATE TABLE staging.pipeline_stage_logs (
    id             BIGSERIAL   PRIMARY KEY,
    run_id         BIGINT      NOT NULL REFERENCES staging.pipeline_runs (id),
    stage_name     VARCHAR(50) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'running',
    input_data     JSONB,
    output_data    JSONB,
    summary_json   JSONB,
    started_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMP,
    failure_reason TEXT,
    CONSTRAINT pipeline_stage_logs_status_check
        CHECK (status IN ('running', 'completed', 'failed', 'skipped'))
);

COMMENT ON COLUMN staging.pipeline_stage_logs.input_data  IS 'Full task input variables captured at stage start.';
COMMENT ON COLUMN staging.pipeline_stage_logs.output_data IS 'Full map returned by the stage module at stage completion.';
COMMENT ON COLUMN staging.pipeline_stage_logs.summary_json IS 'Lightweight scalar metrics only (subset of output_data) — kept for quick dashboards.';

CREATE INDEX idx_pipeline_stage_logs_run_id ON staging.pipeline_stage_logs (run_id);
