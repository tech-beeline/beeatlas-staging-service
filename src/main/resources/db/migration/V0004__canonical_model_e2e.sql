-- Canonical model for artifactType=e2e-sequence: BI steps, interfaces, operations and
-- their versioned snapshots, grouped by artifact_batches (saver's idempotent grouping unit).
--
-- TEMPLATE FOR CONTRIBUTORS: a new entity type defines its own canonical tables in its own
-- V000N migration, following this same identity-table + *_versions + artifact_batches shape.
-- Nothing here is read by the generic pipeline workers — only by E2ECanonicalSaver.

CREATE TABLE staging.artifact_batches (
    id               BIGSERIAL    PRIMARY KEY,
    artifact_uid     VARCHAR(255) NOT NULL,
    artifact_type    VARCHAR(100) NOT NULL,
    run_id           BIGINT       REFERENCES staging.pipeline_runs (id),
    raw_data_ref_id  BIGINT       REFERENCES staging.raw_data_refs (id),
    bi_steps_count   INTEGER      NOT NULL DEFAULT 0,
    interfaces_count INTEGER      NOT NULL DEFAULT 0,
    operations_count INTEGER      NOT NULL DEFAULT 0,
    is_current       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_artifact_batches_current
    ON staging.artifact_batches (artifact_uid, artifact_type)
    WHERE is_current = TRUE;

CREATE INDEX idx_artifact_batches_artifact ON staging.artifact_batches (artifact_uid, artifact_type);
CREATE INDEX idx_artifact_batches_run_id   ON staging.artifact_batches (run_id);

-- ---------------------------------------------------------------------------
-- Core identity tables
-- ---------------------------------------------------------------------------

CREATE TABLE staging.bi_steps (
    id         SERIAL      PRIMARY KEY,
    ref_id     INTEGER     UNIQUE,
    uid        VARCHAR(50) UNIQUE,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.interfaces (
    id         SERIAL      PRIMARY KEY,
    uid        VARCHAR(50) UNIQUE,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.operations (
    id           SERIAL      PRIMARY KEY,
    interface_id INTEGER     REFERENCES staging.interfaces (id),
    name         TEXT,
    type         VARCHAR(50),
    ext_uid      VARCHAR(50) UNIQUE,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- Versioned snapshots (append-only, one row per load, traceable to raw_data_ref + batch)
-- ---------------------------------------------------------------------------

CREATE TABLE staging.bi_step_versions (
    id              SERIAL       PRIMARY KEY,
    bi_step_id      INTEGER      REFERENCES staging.bi_steps (id),
    name            TEXT,
    rps             NUMERIC,
    latency         NUMERIC,
    error_rate      NUMERIC,
    raw_data_ref_id BIGINT       REFERENCES staging.raw_data_refs (id),
    batch_id        BIGINT       REFERENCES staging.artifact_batches (id),
    context         TEXT,
    external_guid   VARCHAR(100),
    source_id       VARCHAR(100),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.interface_versions (
    id              SERIAL    PRIMARY KEY,
    interface_id    INTEGER   REFERENCES staging.interfaces (id),
    ext_uid         TEXT,
    protocol        TEXT,
    raw_data_ref_id BIGINT    REFERENCES staging.raw_data_refs (id),
    batch_id        BIGINT    REFERENCES staging.artifact_batches (id),
    context         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.operation_versions (
    id                   SERIAL    PRIMARY KEY,
    operation_id         INTEGER   REFERENCES staging.operations (id),
    interface_version_id INTEGER   REFERENCES staging.interface_versions (id),
    name                 TEXT      NOT NULL,
    type                 VARCHAR(50),
    rps                  NUMERIC,
    latency              NUMERIC,
    error_rate           NUMERIC,
    raw_data_ref_id      BIGINT    REFERENCES staging.raw_data_refs (id),
    batch_id             BIGINT    REFERENCES staging.artifact_batches (id),
    context              TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.bi_step_relation_versions (
    id                   SERIAL    PRIMARY KEY,
    bi_step_version_id   INTEGER   REFERENCES staging.bi_step_versions (id),
    operation_version_id INTEGER   REFERENCES staging.operation_versions (id),
    call_order           INTEGER,
    stereotype           TEXT,
    raw_data_ref_id       BIGINT    REFERENCES staging.raw_data_refs (id),
    batch_id              BIGINT    REFERENCES staging.artifact_batches (id),
    context               TEXT,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.operation_relation_versions (
    id                          SERIAL    PRIMARY KEY,
    operation_version_id        INTEGER   REFERENCES staging.operation_versions (id),
    callee_operation_version_id INTEGER   REFERENCES staging.operation_versions (id),
    call_order                  INTEGER,
    stereotype                  TEXT,
    raw_data_ref_id             BIGINT    REFERENCES staging.raw_data_refs (id),
    batch_id                    BIGINT    REFERENCES staging.artifact_batches (id),
    context                     TEXT,
    created_at                  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bi_step_versions_batch   ON staging.bi_step_versions (batch_id);
CREATE INDEX idx_interface_versions_batch ON staging.interface_versions (batch_id);
CREATE INDEX idx_operation_versions_batch ON staging.operation_versions (batch_id);
