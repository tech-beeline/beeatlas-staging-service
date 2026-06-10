CREATE TABLE staging.configurations (
    id            BIGSERIAL     PRIMARY KEY,
    artifact_type VARCHAR(100)  NOT NULL,
    source        VARCHAR(50)   NOT NULL,
    source_url    VARCHAR(1000),
    params_json   JSONB,
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_configurations_type ON staging.configurations (artifact_type);

-- Seed data for first iteration
INSERT INTO staging.configurations (artifact_type, source, source_url, enabled)
VALUES
    ('e2e-sequence',       'sparx', NULL, TRUE),
    ('business-capability','sparx', NULL, TRUE);
