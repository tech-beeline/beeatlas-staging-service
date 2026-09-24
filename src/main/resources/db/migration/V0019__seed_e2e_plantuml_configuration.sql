INSERT INTO staging.configurations (code, artifact_type, data_type_id, source_system_id, schedule_interval_seconds, is_active)
VALUES (
    'e2e-plantuml-manual',
    'e2e-plantuml',
    (SELECT id FROM staging.data_types WHERE code = 'e2e-plantuml'),
    (SELECT id FROM staging.source_systems WHERE code = 'beeatlas-ui'),
    NULL,
    true
)
ON CONFLICT (code) DO NOTHING;

COMMENT ON TABLE staging.configurations IS 'Конфигурации пайплайнов; schedule_interval_seconds IS NULL — запуск только вручную через API';
