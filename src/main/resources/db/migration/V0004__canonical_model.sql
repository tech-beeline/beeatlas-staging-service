-- Canonical model: BI steps, interfaces, operations and their versioned snapshots.
-- Versions reference raw_data_refs so each snapshot is traceable to its source load.

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
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- Versioned snapshots (one row per load, referencing the source raw_data_ref)
-- ---------------------------------------------------------------------------

CREATE TABLE staging.bi_step_versions (
    id              SERIAL   PRIMARY KEY,
    bi_step_id      INTEGER  REFERENCES staging.bi_steps (id),
    name            TEXT,
    rps             NUMERIC,
    latency         NUMERIC,
    error_rate      NUMERIC,
    raw_data_ref_id BIGINT   REFERENCES staging.raw_data_refs (id),
    context         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.interface_versions (
    id              SERIAL    PRIMARY KEY,
    interface_id    INTEGER   REFERENCES staging.interfaces (id),
    ext_uid         TEXT,
    protocol        TEXT,
    raw_data_ref_id BIGINT    REFERENCES staging.raw_data_refs (id),
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
    context              TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.bi_step_relation_versions (
    id                   SERIAL    PRIMARY KEY,
    bi_step_version_id   INTEGER   REFERENCES staging.bi_step_versions (id),
    operation_version_id INTEGER   REFERENCES staging.operation_versions (id),
    call_order           INTEGER,
    stereotype           TEXT,
    raw_data_ref_id      BIGINT    REFERENCES staging.raw_data_refs (id),
    context              TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.operation_relation_versions (
    id                          SERIAL    PRIMARY KEY,
    operation_version_id        INTEGER   REFERENCES staging.operation_versions (id),
    callee_operation_version_id INTEGER   REFERENCES staging.operation_versions (id),
    call_order                  INTEGER,
    stereotype                  TEXT,
    raw_data_ref_id             BIGINT    REFERENCES staging.raw_data_refs (id),
    context                     TEXT,
    created_at                  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- Stage tables (publish a consistent snapshot of versioned data)
-- ---------------------------------------------------------------------------

CREATE TABLE staging.stages (
    id         SERIAL    PRIMARY KEY,
    name       TEXT      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.stage_bi_steps (
    id                 SERIAL  PRIMARY KEY,
    stage_id           INTEGER NOT NULL REFERENCES staging.stages (id),
    bi_step_version_id INTEGER REFERENCES staging.bi_step_versions (id),
    status             TEXT    DEFAULT 'active',
    CONSTRAINT stage_bi_steps_status_check CHECK (status IS NULL OR status IN ('active','inactive','deleted')),
    UNIQUE (stage_id, bi_step_version_id)
);

CREATE TABLE staging.stage_operations (
    id                   SERIAL  PRIMARY KEY,
    stage_id             INTEGER REFERENCES staging.stages (id),
    operation_version_id INTEGER REFERENCES staging.operation_versions (id),
    status               TEXT    DEFAULT 'active',
    CONSTRAINT stage_operations_status_check CHECK (status IS NULL OR status IN ('active','inactive','deleted'))
);

CREATE TABLE staging.stage_bi_step_relations (
    id                          SERIAL  PRIMARY KEY,
    stage_id                    INTEGER REFERENCES staging.stages (id),
    bi_step_relation_version_id INTEGER REFERENCES staging.bi_step_relation_versions (id),
    status                      TEXT    DEFAULT 'active',
    CONSTRAINT stage_bi_step_relations_status_check CHECK (status IS NULL OR status IN ('active','inactive','deleted'))
);

CREATE TABLE staging.stage_operation_relations (
    id                            SERIAL  PRIMARY KEY,
    stage_id                      INTEGER REFERENCES staging.stages (id),
    operation_relation_version_id INTEGER REFERENCES staging.operation_relation_versions (id),
    status                        TEXT    DEFAULT 'active',
    CONSTRAINT stage_operation_relations_status_check CHECK (status IS NULL OR status IN ('active','inactive','deleted'))
);
