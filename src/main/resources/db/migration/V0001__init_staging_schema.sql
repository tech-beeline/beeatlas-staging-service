CREATE SCHEMA IF NOT EXISTS staging;

CREATE TABLE staging.raw_data_refs (
    id           BIGSERIAL PRIMARY KEY,
    artifact_uid  VARCHAR(255) NOT NULL,
    artifact_type VARCHAR(100) NOT NULL,
    source_id     VARCHAR(100) NOT NULL,
    s3_bucket     VARCHAR(255) NOT NULL,
    s3_key        VARCHAR(1000) NOT NULL,
    content_hash  VARCHAR(64)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    metadata_json JSONB,
    loaded_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_raw_data_refs_artifact ON staging.raw_data_refs (artifact_uid, artifact_type);
CREATE INDEX idx_raw_data_refs_hash     ON staging.raw_data_refs (content_hash);
CREATE INDEX idx_raw_data_refs_source   ON staging.raw_data_refs (source_id);
