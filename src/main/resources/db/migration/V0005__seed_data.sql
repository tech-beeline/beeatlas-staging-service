-- Seed reference data and the e2e-sequence pipeline configuration.
-- The config JSON key for each stage equals that stage's Camunda external-task topic name —
-- see ru.beeline.staging.service.ModuleResolver.

INSERT INTO staging.data_types (code, description)
VALUES ('e2e-sequence', 'E2E sequences from Sparx');

INSERT INTO staging.source_systems (code, name)
VALUES ('sparx', 'Sparx Enterprise Architect'),
       ('grafana', 'Grafana');

INSERT INTO staging.configurations (code, artifact_type, data_type_id, source_system_id,
                                    config, schedule_interval_seconds, is_active)
VALUES ('e2e-sequence-sparx',
        'e2e-sequence',
        (SELECT id FROM staging.data_types WHERE code = 'e2e-sequence'),
        (SELECT id FROM staging.source_systems WHERE code = 'sparx'),
        '{"pre-adapter":"sparx-e2e-preadapter","adapter":"dashboard-e2e-adapter","validator":"e2e-sequence-validator","transformer":"e2e-sequence-transformer","saver":"e2e-canonical-saver"}'::jsonb,
        21600,
        TRUE);
