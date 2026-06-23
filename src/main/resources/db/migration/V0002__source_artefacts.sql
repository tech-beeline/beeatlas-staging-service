-- Source artefact tracking (dedup bookkeeping) and raw data storage.
-- Monitoring view #1 ("что и откуда скачали, по какому идентификатору"): raw_data_refs.

CREATE TABLE staging.source_artefact_types (
    id               SERIAL    PRIMARY KEY,
    data_type_id     INTEGER   NOT NULL REFERENCES staging.data_types (id),
    source_system_id INTEGER   NOT NULL REFERENCES staging.source_systems (id),
    name             TEXT      NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (data_type_id, source_system_id)
);

CREATE TABLE staging.source_artefacts (
    id                      SERIAL    PRIMARY KEY,
    source_artefact_type_id INTEGER  NOT NULL REFERENCES staging.source_artefact_types (id),
    ext_uid                 TEXT      NOT NULL,
    status                  TEXT      NOT NULL DEFAULT 'active',
    last_loaded_ref_id      BIGINT,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT source_artefacts_status_check CHECK (status IN ('active', 'inactive', 'deleted')),
    UNIQUE (source_artefact_type_id, ext_uid)
);

CREATE INDEX idx_source_artefacts_type    ON staging.source_artefacts (source_artefact_type_id);
CREATE INDEX idx_source_artefacts_ext_uid ON staging.source_artefacts (ext_uid);
CREATE INDEX idx_source_artefacts_status  ON staging.source_artefacts (status);

CREATE TABLE staging.raw_data_refs (
    id                       BIGSERIAL    PRIMARY KEY,
    artifact_uid             VARCHAR(255) NOT NULL,
    artifact_type            VARCHAR(100) NOT NULL,
    source_id                VARCHAR(100) NOT NULL,
    raw_content              BYTEA,
    canonical_snapshot_json  TEXT,
    content_hash             VARCHAR(64)  NOT NULL,
    size_bytes               BIGINT       NOT NULL,
    metadata_json            JSONB,
    loaded_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN staging.raw_data_refs.raw_content IS
    'Gzip-compressed raw payload from the source (downloaded by the adapter module).';
COMMENT ON COLUMN staging.raw_data_refs.canonical_snapshot_json IS
    'JSON produced by the transformer module, consumed by the saver module. Never travels through Camunda process variables (varchar(4000) limit) — only raw_data_ref_id does.';

CREATE INDEX idx_raw_data_refs_artifact ON staging.raw_data_refs (artifact_uid, artifact_type);
CREATE INDEX idx_raw_data_refs_hash     ON staging.raw_data_refs (content_hash);
CREATE INDEX idx_raw_data_refs_source   ON staging.raw_data_refs (source_id);

ALTER TABLE staging.source_artefacts
    ADD CONSTRAINT fk_source_artefacts_last_loaded_ref
    FOREIGN KEY (last_loaded_ref_id) REFERENCES staging.raw_data_refs (id);
