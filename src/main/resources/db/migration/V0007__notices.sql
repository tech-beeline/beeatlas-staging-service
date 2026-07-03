CREATE TABLE staging.notice_types (
    id                   BIGSERIAL    PRIMARY KEY,
    code                 VARCHAR(100) NOT NULL,
    level                VARCHAR(10)  NOT NULL
        CONSTRAINT notice_types_level_check CHECK (level IN ('info', 'warning', 'error')),
    category             VARCHAR(50)  NOT NULL
        CONSTRAINT notice_types_category_check CHECK (category IN ('validation', 'transform', 'match')),
    description          TEXT,
    source_artifact_type VARCHAR(100),
    state                VARCHAR(20)  NOT NULL DEFAULT 'pending'
        CONSTRAINT notice_types_state_check CHECK (state IN ('pending', 'confirmed', 'rejected')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    confirmed_by         VARCHAR(100),
    CONSTRAINT notice_types_code_unique UNIQUE (code)
);

CREATE INDEX idx_notice_types_state    ON staging.notice_types (state);
CREATE INDEX idx_notice_types_category ON staging.notice_types (category);

CREATE TABLE staging.artifact_notices (
    id              BIGSERIAL PRIMARY KEY,
    notice_type_id  BIGINT    NOT NULL REFERENCES staging.notice_types(id),
    raw_data_ref_id BIGINT    REFERENCES staging.raw_data_refs(id),
    context         TEXT,
    details         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_artifact_notices_notice_type  ON staging.artifact_notices (notice_type_id);
CREATE INDEX idx_artifact_notices_raw_data_ref ON staging.artifact_notices (raw_data_ref_id);

ALTER TABLE staging.bi_step_versions   ADD COLUMN match_notice_id BIGINT REFERENCES staging.artifact_notices(id);
ALTER TABLE staging.interface_versions ADD COLUMN match_notice_id BIGINT REFERENCES staging.artifact_notices(id);
ALTER TABLE staging.operation_versions ADD COLUMN match_notice_id BIGINT REFERENCES staging.artifact_notices(id);
ALTER TABLE staging.tc_versions        ADD COLUMN match_notice_id BIGINT REFERENCES staging.artifact_notices(id);
ALTER TABLE staging.sequence_versions  ADD COLUMN match_notice_id BIGINT REFERENCES staging.artifact_notices(id);
