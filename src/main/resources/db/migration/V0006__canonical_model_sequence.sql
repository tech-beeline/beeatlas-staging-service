-- Canonical model for artifactType=sequence: TC (Technology Chain) identity, versioned
-- snapshots, and relations — parallel to the e2e model (bi_steps / bi_step_versions).
--
-- Hierarchy (TC → Sequence 1:N):
--   tc (Technology Chain / Технологическая Цепочка)
--     ├── tc_versions (версии TC с provenance)
--     └── sequences (один TC → одно или несколько Sequence)
--           ├── sequence_versions (версии Sequence с provenance)
--           └── sequence_relation_versions (вызовы внутри Sequence)
--
-- Each sequence records a dynamic view from Structurizr: a start operation and the
-- ordered chain of caller → callee operations that constitute the chain.
--
-- A TC is the product-level analogue of a BI step:
--   - tc (identity table)                        ↔ bi_steps
--   - tc_versions                                ↔ bi_step_versions
--   - sequences                                  ↔ (no direct analogue, product-specific)
--   - sequence_versions                          ↔ bi_step_versions (product-level)
--   - sequence_relation_versions                 ↔ bi_step_relation_versions
--   - stage_tc / stage_tc_versions               ↔ stage_bi_steps
--   - stage_sequences / stage_sequence_relations ↔ stage_bi_step_relations
--
-- Nothing here is read by the generic pipeline workers — only by SequenceModelSaverService.

-- ---------------------------------------------------------------------------
-- Core identity tables
-- ---------------------------------------------------------------------------

CREATE TABLE staging.tc (
    id         SERIAL       PRIMARY KEY,
    tc_code    VARCHAR(100) UNIQUE NOT NULL,
    product_id INTEGER,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.sequences (
    id         SERIAL       PRIMARY KEY,
    tc_id      INTEGER      NOT NULL REFERENCES staging.tc (id),
    tc_code    VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sequences_tc_id   ON staging.sequences (tc_id);
CREATE INDEX idx_sequences_tc_code ON staging.sequences (tc_code);

-- ---------------------------------------------------------------------------
-- Versioned snapshots (append-only, one row per load, traceable to raw_data_ref + batch)
-- ---------------------------------------------------------------------------

CREATE TABLE staging.tc_versions (
    id              SERIAL    PRIMARY KEY,
    tc_id           INTEGER   REFERENCES staging.tc (id),
    name            TEXT,
    description     TEXT,
    raw_data_ref_id BIGINT    REFERENCES staging.raw_data_refs (id),
    batch_id        BIGINT    REFERENCES staging.artifact_batches (id),
    context         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.sequence_versions (
    id              SERIAL    PRIMARY KEY,
    sequence_id     INTEGER   REFERENCES staging.sequences (id),
    name            TEXT,
    description     TEXT,
    tc_id           INTEGER   REFERENCES staging.tc (id),
    raw_data_ref_id BIGINT    REFERENCES staging.raw_data_refs (id),
    batch_id        BIGINT    REFERENCES staging.artifact_batches (id),
    context         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.sequence_relation_versions (
    id                          SERIAL    PRIMARY KEY,
    sequence_version_id         INTEGER   REFERENCES staging.sequence_versions (id),
    caller_operation_version_id INTEGER   REFERENCES staging.operation_versions (id),
    callee_operation_version_id INTEGER   REFERENCES staging.operation_versions (id),
    call_order                  INTEGER,
    stereotype                  TEXT,
    raw_data_ref_id             BIGINT    REFERENCES staging.raw_data_refs (id),
    batch_id                    BIGINT    REFERENCES staging.artifact_batches (id),
    context                     TEXT,
    created_at                  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tc_versions_batch                ON staging.tc_versions (batch_id);
CREATE INDEX idx_sequence_versions_batch          ON staging.sequence_versions (batch_id);
CREATE INDEX idx_sequence_versions_tc_id          ON staging.sequence_versions (tc_id);
CREATE INDEX idx_sequence_relation_versions_batch ON staging.sequence_relation_versions (batch_id);

-- ---------------------------------------------------------------------------
-- Stage tables for TC
-- ---------------------------------------------------------------------------

CREATE TABLE staging.stage_tc (
    id       SERIAL  PRIMARY KEY,
    stage_id INTEGER NOT NULL REFERENCES staging.stages (id),
    tc_id    INTEGER REFERENCES staging.tc (id),
    status   TEXT,

    CONSTRAINT stage_tc_status_check CHECK (status IS NULL OR status IN ('active', 'inactive', 'deleted')),
    CONSTRAINT stage_tc_stage_tc_unique UNIQUE (stage_id, tc_id)
);

CREATE TABLE staging.stage_tc_versions (
    id              SERIAL  PRIMARY KEY,
    stage_id        INTEGER NOT NULL REFERENCES staging.stages (id),
    tc_version_id   INTEGER REFERENCES staging.tc_versions (id),
    status          TEXT,
    next_version_id INTEGER REFERENCES staging.tc_versions (id),

    CONSTRAINT stage_tc_versions_status_check CHECK (status IS NULL OR status IN ('active', 'inactive', 'deleted')),
    CONSTRAINT stage_tc_versions_stage_tc_version_unique UNIQUE (stage_id, tc_version_id)
);

-- ---------------------------------------------------------------------------
-- Stage tables for Sequence
-- ---------------------------------------------------------------------------

CREATE TABLE staging.stage_sequences (
    id                  SERIAL  PRIMARY KEY,
    stage_id            INTEGER NOT NULL REFERENCES staging.stages (id),
    sequence_version_id INTEGER REFERENCES staging.sequence_versions (id),
    status              TEXT,

    CONSTRAINT stage_sequences_status_check CHECK (status IS NULL OR status IN ('active', 'inactive', 'deleted')),
    CONSTRAINT stage_sequences_stage_sequence_version_unique UNIQUE (stage_id, sequence_version_id)
);

CREATE TABLE staging.stage_sequence_relations (
    id                           SERIAL  PRIMARY KEY,
    stage_id                     INTEGER REFERENCES staging.stages (id),
    sequence_relation_version_id INTEGER REFERENCES staging.sequence_relation_versions (id),
    status                       TEXT,

    CONSTRAINT stage_sequence_relations_status_check CHECK (status IS NULL OR status IN ('active', 'inactive', 'deleted'))
);
