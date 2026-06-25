-- Seed reference data and the e2e-sequence pipeline configuration.
-- Which module runs each stage for artifact_type='e2e-sequence' is defined in code —
-- see ru.beeline.staging.pipeline.E2ESequencePipelineDefinition — configurations only
-- carries scheduling/activation now.

INSERT INTO staging.data_types (code, description)
VALUES ('e2e-sequence', 'E2E sequences from Sparx');

INSERT INTO staging.source_systems (code, name)
VALUES ('sparx', 'Sparx Enterprise Architect'),
       ('grafana', 'Grafana');

INSERT INTO staging.configurations (code, artifact_type, data_type_id, source_system_id,
                                    schedule_interval_seconds, is_active)
VALUES ('e2e-sequence-sparx',
        'e2e-sequence',
        (SELECT id FROM staging.data_types WHERE code = 'e2e-sequence'),
        (SELECT id FROM staging.source_systems WHERE code = 'sparx'),
        21600,
        TRUE);
