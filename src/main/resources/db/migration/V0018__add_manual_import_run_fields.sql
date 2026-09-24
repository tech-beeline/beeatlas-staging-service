ALTER TABLE staging.pipeline_runs
    ADD COLUMN IF NOT EXISTS payload jsonb,
    ADD COLUMN IF NOT EXISTS source_id integer,
    ADD COLUMN IF NOT EXISTS branch varchar(64),
    ADD COLUMN IF NOT EXISTS supersedes_run_id bigint;

ALTER TABLE staging.pipeline_runs
    DROP CONSTRAINT IF EXISTS fk_pipeline_runs_source_system;

ALTER TABLE staging.pipeline_runs
    ADD CONSTRAINT fk_pipeline_runs_source_system
        FOREIGN KEY (source_id) REFERENCES staging.source_systems (id);

COMMENT ON COLUMN staging.pipeline_runs.payload IS 'Данные ручного запуска для адаптера (POST /api/v1/pipeline-runs)';
COMMENT ON COLUMN staging.pipeline_runs.source_id IS 'Источник инициации запуска (staging.source_systems)';
COMMENT ON COLUMN staging.pipeline_runs.branch IS 'Ветка запуска для ветвящихся типов, по умолчанию main (ADR-021)';
COMMENT ON COLUMN staging.pipeline_runs.supersedes_run_id IS 'Отменённый этим запуском предыдущий запуск (BR-009-06)';

INSERT INTO staging.data_types (code, description)
VALUES
    ('usecase', 'UseCase import from PlantUML sequence diagram (human-in-the-loop)'),
    ('e2e-plantuml', 'E2E scenario import from PlantUML sequence diagram (manual, no HITL)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO staging.source_systems (code, name)
VALUES
    ('beeatlas-ui', 'BeeAtlas UI'),
    ('solution-checker', 'Solution Checker'),
    ('confluence', 'Confluence')
ON CONFLICT (code) DO NOTHING;
