-- Foundation: schema + reference tables.
-- Which concrete module runs each of the 5 pipeline stages (pre-adapter/adapter/validator/
-- transformer/saver) for a given artifactType is defined in code, not here — see
-- ru.beeline.staging.pipeline.PipelineDefinition and ru.beeline.staging.service.ModuleResolver.
-- staging.module_catalog and staging.pipeline_definitions below are a generated reflection of
-- that code, rebuilt on every startup (ru.beeline.staging.service.ModuleCatalogPublisher) —
-- never edited directly.

CREATE SCHEMA IF NOT EXISTS staging;

CREATE TABLE staging.data_types (
    id          SERIAL      PRIMARY KEY,
    code        VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.source_systems (
    id         SERIAL       PRIMARY KEY,
    code       VARCHAR(50)  NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    address    TEXT,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE staging.configurations (
    id                        BIGSERIAL    PRIMARY KEY,
    code                      VARCHAR(100) NOT NULL UNIQUE,
    artifact_type             VARCHAR(100) NOT NULL,
    data_type_id              INTEGER      NOT NULL REFERENCES staging.data_types (id),
    source_system_id          INTEGER      REFERENCES staging.source_systems (id),
    schedule_interval_seconds BIGINT,
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_configurations_data_type_id ON staging.configurations (data_type_id);
CREATE INDEX idx_configurations_is_active    ON staging.configurations (is_active);

CREATE TABLE staging.module_catalog (
    module_code VARCHAR(100) PRIMARY KEY,
    module_type VARCHAR(50)  NOT NULL,
    description TEXT         NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT module_catalog_module_type_check CHECK (
        module_type IN ('pre-adapter', 'adapter', 'validator', 'transformer', 'saver')
    )
);

COMMENT ON TABLE staging.module_catalog IS
    'Snapshot of every module bean registered in code, refreshed on each startup — see ModuleCatalogPublisher.';

CREATE TABLE staging.pipeline_definitions (
    id            BIGSERIAL    PRIMARY KEY,
    artifact_type VARCHAR(100) NOT NULL,
    stage         VARCHAR(50)  NOT NULL,
    stage_order   INTEGER      NOT NULL,
    module_code   VARCHAR(100) NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pipeline_definitions_unique UNIQUE (artifact_type, stage)
);

CREATE INDEX idx_pipeline_definitions_artifact_type ON staging.pipeline_definitions (artifact_type);

COMMENT ON TABLE staging.pipeline_definitions IS
    'Snapshot of every PipelineDefinition bean''s moduleMap(), refreshed on each startup — see ModuleCatalogPublisher.';
