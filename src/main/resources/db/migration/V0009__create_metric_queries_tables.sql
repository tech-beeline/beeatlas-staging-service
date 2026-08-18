-- ============================================================
-- V0009: metric-queries pipeline (SFDM-3844)
-- ============================================================
-- Новый artifactType 'metric-queries': выгрузка запросов метрик из Sparx EA
-- (метаданные, property api-metric-template) + Grafana (targets), публикация
-- в dashboard-service (ADR-012). См. documentation/staging-service/
-- source-artefacts/metric-queries/README.md и model/tables/metric_query_template*.md.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Seed: data_types + configurations (переиспользуем существующий
--    source_systems 'sparx' — та же Sparx EA, что и у e2e-sequence)
-- ------------------------------------------------------------
INSERT INTO staging.data_types (code, description)
VALUES ('metric-queries', 'Grafana dashboard metric queries (Sparx EA api-metric-template)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO staging.configurations (code, artifact_type, data_type_id, source_system_id, schedule_interval_seconds, is_active)
VALUES (
    'metric-queries-default', 'metric-queries',
    (SELECT id FROM staging.data_types WHERE code = 'metric-queries'),
    (SELECT id FROM staging.source_systems WHERE code = 'sparx'),
    3600, true
)
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------
-- 2. metric_query_templates (identity)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.metric_query_templates (
    id          bigserial PRIMARY KEY,
    uid         text NOT NULL UNIQUE,
    entity_type varchar(20) NOT NULL,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.metric_query_templates IS
    'Канонический справочник объектов запросов метрик — носителей property api-metric-template (ADR-012, REQ-staging-007)';
COMMENT ON COLUMN staging.metric_query_templates.id IS 'Идентификатор носителя в staging-service';
COMMENT ON COLUMN staging.metric_query_templates.uid IS 'Единый идентификатор носителя (COALESCE(alias, ea_guid) объекта Sparx EA)';
COMMENT ON COLUMN staging.metric_query_templates.entity_type IS 'Тип сущности-носителя: product/container/interface/object';
COMMENT ON COLUMN staging.metric_query_templates.created_at IS 'Дата и время создания записи';

CREATE INDEX IF NOT EXISTS idx_metric_query_templates_entity_type
    ON staging.metric_query_templates (entity_type);

-- ------------------------------------------------------------
-- 3. metric_query_template_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.metric_query_template_versions (
    id                          bigserial PRIMARY KEY,
    metric_query_template_id   bigint NOT NULL REFERENCES staging.metric_query_templates (id),
    schema_version              varchar(20) NOT NULL,
    json_data                   jsonb NOT NULL,
    batch_id                    bigint REFERENCES staging.artifact_batches (id),
    is_current                  boolean NOT NULL DEFAULT true,
    raw_data_context_id         bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id             bigint REFERENCES staging.artifact_notices (id),
    created_at                  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.metric_query_template_versions IS
    'Версии снимков запросов метрик — публикация одного объекта-источника (entity_type+uid+metricTemplates), ADR-012';
COMMENT ON COLUMN staging.metric_query_template_versions.id IS 'Идентификатор версии';
COMMENT ON COLUMN staging.metric_query_template_versions.metric_query_template_id IS 'Ссылка на носителя в справочнике';
COMMENT ON COLUMN staging.metric_query_template_versions.schema_version IS 'Версия формата публикации (контракт: "1.0")';
COMMENT ON COLUMN staging.metric_query_template_versions.json_data IS 'Снимок metricTemplates: массив {metric_code, template} (может быть пустым, EC-007-03)';
COMMENT ON COLUMN staging.metric_query_template_versions.batch_id IS 'Батч, в рамках которого сохранена версия';
COMMENT ON COLUMN staging.metric_query_template_versions.is_current IS 'Флаг актуальности версии (перезапись при повторной публикации по uid/entity_type)';
COMMENT ON COLUMN staging.metric_query_template_versions.raw_data_context_id IS 'Корневой контекст сырых данных (метаданные Sparx + targets Grafana)';
COMMENT ON COLUMN staging.metric_query_template_versions.match_notice_id IS 'Match-замечание: причина привязки к носителю';
COMMENT ON COLUMN staging.metric_query_template_versions.created_at IS 'Дата и время создания версии';

CREATE INDEX IF NOT EXISTS idx_metric_query_template_versions_mqt_id
    ON staging.metric_query_template_versions (metric_query_template_id);
CREATE INDEX IF NOT EXISTS idx_metric_query_template_versions_batch_id
    ON staging.metric_query_template_versions (batch_id);
CREATE INDEX IF NOT EXISTS idx_metric_query_template_versions_is_current
    ON staging.metric_query_template_versions (is_current);
CREATE INDEX IF NOT EXISTS idx_metric_query_template_versions_raw_data_context_id
    ON staging.metric_query_template_versions (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_metric_query_template_versions_match_notice_id
    ON staging.metric_query_template_versions (match_notice_id);
