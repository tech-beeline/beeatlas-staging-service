-- ============================================================
-- V0005: Seed data_types / source_systems / configurations
-- Nothing auto-derives these from code (unlike module_catalog and
-- pipeline_definitions, which ModuleCatalogPublisher syncs on every
-- startup) — without a row in staging.configurations, PipelineTickScheduler
-- finds zero active candidates and the pipeline never fires, silently.
-- ============================================================

INSERT INTO staging.data_types (code, description)
VALUES
    ('structurizr-sequence', 'Sequence diagrams (Dynamic Views) exported from Structurizr workspace.json'),
    ('e2e-sequence', 'E2E scenario export from Sparx EA')
ON CONFLICT (code) DO NOTHING;

INSERT INTO staging.source_systems (code, name)
VALUES
    ('structurizr', 'Structurizr'),
    ('sparx', 'Sparx EA')
ON CONFLICT (code) DO NOTHING;

INSERT INTO staging.configurations (code, artifact_type, data_type_id, source_system_id, schedule_interval_seconds, is_active)
VALUES
    ('structurizr-sequence-default',
     'structurizr-sequence',
     (SELECT id FROM staging.data_types WHERE code = 'structurizr-sequence'),
     (SELECT id FROM staging.source_systems WHERE code = 'structurizr'),
     3600,
     true),
    ('e2e-sequence-default',
     'e2e-sequence',
     (SELECT id FROM staging.data_types WHERE code = 'e2e-sequence'),
     (SELECT id FROM staging.source_systems WHERE code = 'sparx'),
     3600,
     true)
ON CONFLICT (code) DO NOTHING;
