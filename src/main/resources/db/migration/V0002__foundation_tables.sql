-- Reference tables: data types, source systems, configurations

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
    config                    JSONB,
    schedule_interval_seconds BIGINT,
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_configurations_data_type_id ON staging.configurations (data_type_id);
CREATE INDEX idx_configurations_is_active    ON staging.configurations (is_active);

-- ---------------------------------------------------------------------------
-- Seed data
-- ---------------------------------------------------------------------------

INSERT INTO staging.data_types (code, description)
VALUES ('e2e-sequence',        'E2E sequences from Sparx'),
       ('business-capability', 'Business capabilities from Sparx');

INSERT INTO staging.source_systems (code, name)
VALUES ('sparx',   'Sparx Enterprise Architect'),
       ('grafana', 'Grafana');

INSERT INTO staging.configurations (code, artifact_type, data_type_id, source_system_id,
                                    config, schedule_interval_seconds, is_active)
VALUES ('e2e-sequence-sparx',
        'e2e-sequence',
        (SELECT id FROM staging.data_types WHERE code = 'e2e-sequence'),
        (SELECT id FROM staging.source_systems WHERE code = 'sparx'),
        '{"adapter": "sparx-e2e-adapter"}'::jsonb,
        21600,
        TRUE),
       ('business-capability-sparx',
        'business-capability',
        (SELECT id FROM staging.data_types WHERE code = 'business-capability'),
        (SELECT id FROM staging.source_systems WHERE code = 'sparx'),
        '{"adapter": "sparx-bc-adapter"}'::jsonb,
        21600,
        TRUE);
