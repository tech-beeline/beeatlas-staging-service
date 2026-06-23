-- Foundation: schema + reference tables + pipeline configuration.
-- configurations.config is the single place that names, per entity, which concrete
-- module runs each of the 5 pipeline stages (pre-adapter/adapter/validator/transformer/saver).
-- See ru.beeline.staging.service.ModuleResolver for how this JSON is consumed.

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
    config                    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    schedule_interval_seconds BIGINT,
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN staging.configurations.config IS
    'JSON map: stage topic name -> moduleCode, e.g. {"pre-adapter":"sparx-e2e-preadapter","adapter":"dashboard-e2e-adapter","validator":"e2e-sequence-validator","transformer":"e2e-sequence-transformer","saver":"e2e-canonical-saver"}';

CREATE INDEX idx_configurations_data_type_id ON staging.configurations (data_type_id);
CREATE INDEX idx_configurations_is_active    ON staging.configurations (is_active);
