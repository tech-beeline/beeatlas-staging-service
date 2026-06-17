-- Pipeline run tracking: one row per Camunda process instance (per artifact per trigger).
-- Each pipeline run tracks overall status and, via pipeline_stage_logs, per-stage progress.
-- This is our own observability layer on top of Camunda History — queryable without Cockpit.

CREATE TABLE staging.pipeline_runs (
    id               BIGSERIAL    PRIMARY KEY,
    artifact_uid     VARCHAR(255) NOT NULL,
    artifact_type    VARCHAR(100) NOT NULL,
    configuration_id BIGINT       REFERENCES staging.configurations(id),
    raw_data_ref_id  BIGINT       REFERENCES staging.raw_data_refs(id),  -- filled after loader stage
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending',
    camunda_pid      VARCHAR(255),                                        -- Camunda process instance id for cross-reference
    started_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMP,
    failure_reason   TEXT,
    failed_stage     VARCHAR(50),
    CONSTRAINT pipeline_runs_status_check
        CHECK (status IN ('pending','loading','validating','transforming','saving','publishing','completed','failed'))
);

CREATE INDEX idx_pipeline_runs_artifact ON staging.pipeline_runs(artifact_uid, artifact_type);
CREATE INDEX idx_pipeline_runs_status   ON staging.pipeline_runs(status);
CREATE INDEX idx_pipeline_runs_started  ON staging.pipeline_runs(started_at DESC);

-- One row per stage within a pipeline run.
-- stage_name values: loader | validator | transformer | saver | publisher
CREATE TABLE staging.pipeline_stage_logs (
    id             BIGSERIAL   PRIMARY KEY,
    run_id         BIGINT      NOT NULL REFERENCES staging.pipeline_runs(id),
    stage_name     VARCHAR(50) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'running',
    started_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMP,
    failure_reason TEXT,
    summary_json   JSONB,   -- counts/metrics produced by this stage (e.g. {biStepsSaved: 12})
    CONSTRAINT pipeline_stage_logs_status_check
        CHECK (status IN ('running','completed','failed','skipped'))
);

CREATE INDEX idx_pipeline_stage_logs_run_id ON staging.pipeline_stage_logs(run_id);
