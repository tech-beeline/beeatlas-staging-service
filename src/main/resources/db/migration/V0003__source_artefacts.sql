-- Source artefact types and artefact instances (used for deduplication)

CREATE TABLE staging.source_artefact_types (
    id               SERIAL    PRIMARY KEY,
    data_type_id     INTEGER   NOT NULL REFERENCES staging.data_types (id),
    source_system_id INTEGER   NOT NULL REFERENCES staging.source_systems (id),
    name             TEXT      NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (data_type_id, source_system_id)
);

-- Tracks each artifact instance in the source, with pointer to the last raw load.
-- Used by LoaderWorker to detect unchanged content (content_hash comparison).
CREATE TABLE staging.source_artefacts (
    id                      SERIAL    PRIMARY KEY,
    source_artefact_type_id INTEGER   NOT NULL REFERENCES staging.source_artefact_types (id),
    ext_uid                 TEXT      NOT NULL,
    status                  TEXT      NOT NULL DEFAULT 'active',
    last_loaded_ref_id      BIGINT    REFERENCES staging.raw_data_refs (id),
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT source_artefacts_status_check CHECK (status IN ('active', 'inactive', 'deleted')),
    UNIQUE (source_artefact_type_id, ext_uid)
);

CREATE INDEX idx_source_artefacts_type    ON staging.source_artefacts (source_artefact_type_id);
CREATE INDEX idx_source_artefacts_ext_uid ON staging.source_artefacts (ext_uid);
CREATE INDEX idx_source_artefacts_status  ON staging.source_artefacts (status);
