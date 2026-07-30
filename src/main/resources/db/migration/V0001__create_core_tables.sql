-- ============================================================
-- V0001: Create core tables (ядро)
-- Справочники, конфигурации, load state, pipeline, замечания
-- ============================================================

-- ------------------------------------------------------------
-- Schema
-- ------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS staging;

-- ------------------------------------------------------------
-- 1. data_types
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.data_types (
    id              serial PRIMARY KEY,
    code            varchar(50) NOT NULL UNIQUE,
    description     text,
    created_at      timestamp DEFAULT now()
);
COMMENT ON TABLE staging.data_types IS 'Справочник типов данных (e2e-sequence, products, fdm и т.д.)';
COMMENT ON COLUMN staging.data_types.id IS 'Идентификатор типа данных';
COMMENT ON COLUMN staging.data_types.code IS 'Уникальный код типа (e.g. e2e-sequence)';
COMMENT ON COLUMN staging.data_types.description IS 'Описание типа данных';
COMMENT ON COLUMN staging.data_types.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 2. source_systems
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.source_systems (
    id              serial PRIMARY KEY,
    code            varchar(50) NOT NULL UNIQUE,
    name            varchar(255) NOT NULL,
    address         text,
    created_at      timestamp DEFAULT now()
);
COMMENT ON TABLE staging.source_systems IS 'Справочник систем-источников данных (Sparx EA, cx-backend и т.д.)';
COMMENT ON COLUMN staging.source_systems.id IS 'Идентификатор источника данных';
COMMENT ON COLUMN staging.source_systems.code IS 'Уникальный код источника (e.g. sparx)';
COMMENT ON COLUMN staging.source_systems.name IS 'Полное наименование источника';
COMMENT ON COLUMN staging.source_systems.address IS 'Адрес подключения к источнику';
COMMENT ON COLUMN staging.source_systems.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 3. notice_types
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.notice_types (
    id                      bigserial PRIMARY KEY,
    code                    varchar(100) NOT NULL UNIQUE,
    level                   varchar(10) NOT NULL,
    category                varchar(50) NOT NULL,
    description             text,
    source_artifact_type    varchar(100),
    state                   varchar(20) DEFAULT 'pending',
    created_at              timestamp DEFAULT now(),
    updated_at              timestamp DEFAULT now(),
    confirmed_by            varchar(100)
);
COMMENT ON TABLE staging.notice_types IS 'Каталог типов замечаний с авто-регистрацией';
COMMENT ON COLUMN staging.notice_types.id IS 'Идентификатор типа замечания';
COMMENT ON COLUMN staging.notice_types.code IS 'Уникальный код типа (e.g. validation.missing_required_field)';
COMMENT ON COLUMN staging.notice_types.level IS 'Уровень: info, warning, error';
COMMENT ON COLUMN staging.notice_types.category IS 'Категория: validation, transform, match';
COMMENT ON COLUMN staging.notice_types.description IS 'Человекочитаемое описание';
COMMENT ON COLUMN staging.notice_types.source_artifact_type IS 'Тип источника (NULL = общий для всех типов)';
COMMENT ON COLUMN staging.notice_types.state IS 'Состояние: pending, confirmed, rejected';
COMMENT ON COLUMN staging.notice_types.created_at IS 'Дата создания';
COMMENT ON COLUMN staging.notice_types.updated_at IS 'Дата обновления';
COMMENT ON COLUMN staging.notice_types.confirmed_by IS 'Кто подтвердил';

CREATE INDEX IF NOT EXISTS idx_notice_types_state ON staging.notice_types (state);
CREATE INDEX IF NOT EXISTS idx_notice_types_category ON staging.notice_types (category);

-- ------------------------------------------------------------
-- 4. stages
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.stages (
    id              bigserial PRIMARY KEY,
    name            text NOT NULL
);
COMMENT ON TABLE staging.stages IS 'Справочник этапов архитектуры';
COMMENT ON COLUMN staging.stages.id IS 'Идентификатор этапа';
COMMENT ON COLUMN staging.stages.name IS 'Наименование этапа (e.g. Frontend, Backend)';

-- ------------------------------------------------------------
-- 5. configurations
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.configurations (
    id                          bigserial PRIMARY KEY,
    code                        varchar(100) NOT NULL UNIQUE,
    artifact_type               varchar(100) NOT NULL,
    data_type_id                integer NOT NULL,
    source_system_id            integer,
    schedule_interval_seconds   bigint,
    is_active                   boolean DEFAULT true,
    created_at                  timestamp DEFAULT now(),
    updated_at                  timestamp DEFAULT now(),
    CONSTRAINT fk_configurations_data_type_id
        FOREIGN KEY (data_type_id) REFERENCES staging.data_types (id),
    CONSTRAINT fk_configurations_source_system_id
        FOREIGN KEY (source_system_id) REFERENCES staging.source_systems (id)
);
COMMENT ON TABLE staging.configurations IS 'Конфигурации загрузки данных из источников';
COMMENT ON COLUMN staging.configurations.id IS 'Идентификатор конфигурации';
COMMENT ON COLUMN staging.configurations.code IS 'Уникальный код конфигурации';
COMMENT ON COLUMN staging.configurations.artifact_type IS 'Тип артефакта';
COMMENT ON COLUMN staging.configurations.data_type_id IS 'Тип данных';
COMMENT ON COLUMN staging.configurations.source_system_id IS 'Источник данных';
COMMENT ON COLUMN staging.configurations.schedule_interval_seconds IS 'Интервал запуска в секундах (NULL для ручного запуска)';
COMMENT ON COLUMN staging.configurations.is_active IS 'Флаг активности';
COMMENT ON COLUMN staging.configurations.created_at IS 'Дата и время создания';
COMMENT ON COLUMN staging.configurations.updated_at IS 'Дата и время последнего обновления';

CREATE INDEX IF NOT EXISTS idx_configurations_data_type_id ON staging.configurations (data_type_id);
CREATE INDEX IF NOT EXISTS idx_configurations_is_active ON staging.configurations (is_active);

-- ------------------------------------------------------------
-- 6. module_catalog
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.module_catalog (
    module_code     varchar(100) PRIMARY KEY,
    module_type     varchar(50) NOT NULL,
    description     text NOT NULL,
    updated_at      timestamp DEFAULT now()
);
COMMENT ON TABLE staging.module_catalog IS 'Каталог модулей пайплайна, синхронизируется при каждом запуске приложения';
COMMENT ON COLUMN staging.module_catalog.module_code IS 'Код модуля';
COMMENT ON COLUMN staging.module_catalog.module_type IS 'Тип модуля: pre-adapter, adapter, validator, transformer, saver';
COMMENT ON COLUMN staging.module_catalog.description IS 'Описание модуля';
COMMENT ON COLUMN staging.module_catalog.updated_at IS 'Дата и время последнего обновления';

-- ------------------------------------------------------------
-- 7. pipeline_definitions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.pipeline_definitions (
    id                  bigserial PRIMARY KEY,
    artifact_type       varchar(100) NOT NULL,
    modules_sequence    jsonb NOT NULL,
    is_current          boolean DEFAULT true,
    created_at          timestamp DEFAULT now()
);
COMMENT ON TABLE staging.pipeline_definitions IS 'Версионированный список модулей для каждого типа артефакта';
COMMENT ON COLUMN staging.pipeline_definitions.id IS 'Идентификатор версии пайплайна';
COMMENT ON COLUMN staging.pipeline_definitions.artifact_type IS 'Тип артефакта';
COMMENT ON COLUMN staging.pipeline_definitions.modules_sequence IS 'Упорядоченный массив объектов {stage, moduleCode}';
COMMENT ON COLUMN staging.pipeline_definitions.is_current IS 'Флаг актуальности версии';
COMMENT ON COLUMN staging.pipeline_definitions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_pipeline_definitions_artifact_type_is_current
    ON staging.pipeline_definitions (artifact_type, is_current);

-- ------------------------------------------------------------
-- 8. source_artifact_types
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.source_artifact_types (
    id                      serial PRIMARY KEY,
    data_type_id            integer NOT NULL,
    source_system_id        integer NOT NULL,
    name                    text NOT NULL,
    created_at              timestamp DEFAULT now(),
    CONSTRAINT fk_source_artifact_types_data_type_id
        FOREIGN KEY (data_type_id) REFERENCES staging.data_types (id),
    CONSTRAINT fk_source_artifact_types_source_system_id
        FOREIGN KEY (source_system_id) REFERENCES staging.source_systems (id)
);
COMMENT ON TABLE staging.source_artifact_types IS 'Типы артефактов в терминах источника (e.g. сценарии e2e, контейнеры, API)';
COMMENT ON COLUMN staging.source_artifact_types.id IS 'Идентификатор типа артефакта';
COMMENT ON COLUMN staging.source_artifact_types.data_type_id IS 'Тип данных';
COMMENT ON COLUMN staging.source_artifact_types.source_system_id IS 'Источник данных';
COMMENT ON COLUMN staging.source_artifact_types.name IS 'Название артефакта в терминах источника';
COMMENT ON COLUMN staging.source_artifact_types.created_at IS 'Дата и время создания';

CREATE UNIQUE INDEX IF NOT EXISTS idx_source_artifact_types_data_source
    ON staging.source_artifact_types (data_type_id, source_system_id);

-- ------------------------------------------------------------
-- 9. raw_data_refs
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.raw_data_refs (
    id                          bigserial PRIMARY KEY,
    artifact_uid                text,
    artifact_type               varchar(100) NOT NULL,
    source_id                   varchar(100) NOT NULL,
    format                      varchar(50) NOT NULL,
    raw_content                 bytea,
    canonical_snapshot_json     text,
    content_hash                varchar(64) NOT NULL,
    size_bytes                  bigint NOT NULL,
    metadata_json               jsonb,
    loaded_at                   timestamp DEFAULT now(),
    updated_at                  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.raw_data_refs IS 'Хранилище сырых данных и канонических снимков артефактов';
COMMENT ON COLUMN staging.raw_data_refs.id IS 'Идентификатор сырых данных';
COMMENT ON COLUMN staging.raw_data_refs.artifact_uid IS 'Уникальный идентификатор артефакта в системе';
COMMENT ON COLUMN staging.raw_data_refs.artifact_type IS 'Тип артефакта';
COMMENT ON COLUMN staging.raw_data_refs.source_id IS 'Идентификатор источника';
COMMENT ON COLUMN staging.raw_data_refs.format IS 'Формат данных: json, yaml, xml, text, binary';
COMMENT ON COLUMN staging.raw_data_refs.raw_content IS 'Gzip-компрессированные сырые данные из источника';
COMMENT ON COLUMN staging.raw_data_refs.canonical_snapshot_json IS 'JSON канонического снимка (из Transformer)';
COMMENT ON COLUMN staging.raw_data_refs.content_hash IS 'Хэш содержимого для дедупликации';
COMMENT ON COLUMN staging.raw_data_refs.size_bytes IS 'Размер в байтах';
COMMENT ON COLUMN staging.raw_data_refs.metadata_json IS 'Метаданные из Pre-Adapter';
COMMENT ON COLUMN staging.raw_data_refs.loaded_at IS 'Дата и время загрузки';
COMMENT ON COLUMN staging.raw_data_refs.updated_at IS 'Дата и время последнего обновления';

CREATE INDEX IF NOT EXISTS idx_raw_data_refs_artifact_uid_type
    ON staging.raw_data_refs (artifact_uid, artifact_type);
CREATE INDEX IF NOT EXISTS idx_raw_data_refs_content_hash
    ON staging.raw_data_refs (content_hash);
CREATE INDEX IF NOT EXISTS idx_raw_data_refs_source_id
    ON staging.raw_data_refs (source_id);

-- ------------------------------------------------------------
-- 10. raw_data_contexts
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.raw_data_contexts (
    id                  bigserial PRIMARY KEY,
    raw_data_ref_id     bigint NOT NULL,
    position            jsonb NOT NULL,
    CONSTRAINT fk_raw_data_contexts_raw_data_ref_id
        FOREIGN KEY (raw_data_ref_id) REFERENCES staging.raw_data_refs (id)
);
COMMENT ON TABLE staging.raw_data_contexts IS 'Контекст навигации в сырых данных. Минимальная структура согласно ADR-005';
COMMENT ON COLUMN staging.raw_data_contexts.id IS 'Уникальный идентификатор записи контекста';
COMMENT ON COLUMN staging.raw_data_contexts.raw_data_ref_id IS 'Сырые данные';
COMMENT ON COLUMN staging.raw_data_contexts.position IS 'Значение навигации (path, line_range, byte_range)';

CREATE INDEX IF NOT EXISTS idx_raw_data_contexts_raw_data_ref_id
    ON staging.raw_data_contexts (raw_data_ref_id);

-- ------------------------------------------------------------
-- 11. pipeline_runs (needed before source_artifacts, artifact_batches)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.pipeline_runs (
    id                        bigserial PRIMARY KEY,
    artifact_uid              text,
    artifact_type             varchar(100) NOT NULL,
    configuration_id          bigint,
    raw_data_ref_id           bigint,
    batch_id                  varchar(100),
    status                    varchar(20) NOT NULL DEFAULT 'pending',
    camunda_pid               varchar(255),
    execution_id              varchar(255),
    started_at                timestamp NOT NULL DEFAULT now(),
    completed_at              timestamp,
    failure_reason            text,
    failed_stage              varchar(50),
    pipeline_definition_id    bigint,
    parent_run_id             bigint,
    CONSTRAINT fk_pipeline_runs_configuration_id
        FOREIGN KEY (configuration_id) REFERENCES staging.configurations (id),
    CONSTRAINT fk_pipeline_runs_raw_data_ref_id
        FOREIGN KEY (raw_data_ref_id) REFERENCES staging.raw_data_refs (id),
    CONSTRAINT fk_pipeline_runs_pipeline_definition_id
        FOREIGN KEY (pipeline_definition_id) REFERENCES staging.pipeline_definitions (id),
    CONSTRAINT fk_pipeline_runs_parent_run_id
        FOREIGN KEY (parent_run_id) REFERENCES staging.pipeline_runs (id)
);
COMMENT ON TABLE staging.pipeline_runs IS 'Журнал запусков пайплайна обработки данных';
COMMENT ON COLUMN staging.pipeline_runs.id IS 'Идентификатор запуска пайплайна';
COMMENT ON COLUMN staging.pipeline_runs.artifact_uid IS 'NULL для записи-скана, UID артефакта для обработки';
COMMENT ON COLUMN staging.pipeline_runs.artifact_type IS 'Тип артефакта';
COMMENT ON COLUMN staging.pipeline_runs.configuration_id IS 'Конфигурация выгрузки';
COMMENT ON COLUMN staging.pipeline_runs.raw_data_ref_id IS 'Сырые данные';
COMMENT ON COLUMN staging.pipeline_runs.batch_id IS 'Instance ID процесса Camunda (общий для батча)';
COMMENT ON COLUMN staging.pipeline_runs.status IS 'Статус: pending, loading, validating, transforming, saving, publishing, completed, failed';
COMMENT ON COLUMN staging.pipeline_runs.camunda_pid IS 'Process Instance ID Camunda';
COMMENT ON COLUMN staging.pipeline_runs.execution_id IS 'Execution ID для multi-instance итерации';
COMMENT ON COLUMN staging.pipeline_runs.started_at IS 'Дата и время начала';
COMMENT ON COLUMN staging.pipeline_runs.completed_at IS 'Дата и время завершения';
COMMENT ON COLUMN staging.pipeline_runs.failure_reason IS 'Причина ошибки';
COMMENT ON COLUMN staging.pipeline_runs.failed_stage IS 'Название стадии с ошибкой';
COMMENT ON COLUMN staging.pipeline_runs.pipeline_definition_id IS 'Версия пайплайна';
COMMENT ON COLUMN staging.pipeline_runs.parent_run_id IS 'Родительский скан (для артефактов)';

CREATE INDEX IF NOT EXISTS idx_pipeline_runs_artifact_uid_type
    ON staging.pipeline_runs (artifact_uid, artifact_type);
CREATE INDEX IF NOT EXISTS idx_pipeline_runs_status
    ON staging.pipeline_runs (status);
CREATE INDEX IF NOT EXISTS idx_pipeline_runs_started_at
    ON staging.pipeline_runs (started_at);
CREATE INDEX IF NOT EXISTS idx_pipeline_runs_batch_id
    ON staging.pipeline_runs (batch_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_runs_parent_run_id
    ON staging.pipeline_runs (parent_run_id);

-- ------------------------------------------------------------
-- 12. source_artifacts
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.source_artifacts (
    id                          serial PRIMARY KEY,
    source_artifact_type_id     integer NOT NULL,
    ext_uid                     text NOT NULL,
    status                      text NOT NULL DEFAULT 'active',
    last_loaded_ref_id          bigint,
    last_run_id                 bigint,
    last_seen_scan_run_id       bigint,
    created_at                  timestamp DEFAULT now(),
    updated_at                  timestamp DEFAULT now(),
    CONSTRAINT fk_source_artifacts_source_artifact_type_id
        FOREIGN KEY (source_artifact_type_id) REFERENCES staging.source_artifact_types (id),
    CONSTRAINT fk_source_artifacts_last_loaded_ref_id
        FOREIGN KEY (last_loaded_ref_id) REFERENCES staging.raw_data_refs (id),
    CONSTRAINT fk_source_artifacts_last_run_id
        FOREIGN KEY (last_run_id) REFERENCES staging.pipeline_runs (id),
    CONSTRAINT fk_source_artifacts_last_seen_scan_run_id
        FOREIGN KEY (last_seen_scan_run_id) REFERENCES staging.pipeline_runs (id)
);
COMMENT ON TABLE staging.source_artifacts IS 'Identity/dedup bookkeeping для артефактов источника';
COMMENT ON COLUMN staging.source_artifacts.id IS 'Идентификатор артефакта';
COMMENT ON COLUMN staging.source_artifacts.source_artifact_type_id IS 'Тип артефакта';
COMMENT ON COLUMN staging.source_artifacts.ext_uid IS 'Уникальный идентификатор артефакта в источнике';
COMMENT ON COLUMN staging.source_artifacts.status IS 'Статус: active, inactive, deleted';
COMMENT ON COLUMN staging.source_artifacts.last_loaded_ref_id IS 'Последняя загрузка данных';
COMMENT ON COLUMN staging.source_artifacts.last_run_id IS 'Последний запуск для артефакта';
COMMENT ON COLUMN staging.source_artifacts.last_seen_scan_run_id IS 'Последний скан источника преадаптером';
COMMENT ON COLUMN staging.source_artifacts.created_at IS 'Дата и время создания';
COMMENT ON COLUMN staging.source_artifacts.updated_at IS 'Дата и время последнего обновления';

CREATE INDEX IF NOT EXISTS idx_source_artifacts_source_artifact_type_id
    ON staging.source_artifacts (source_artifact_type_id);
CREATE INDEX IF NOT EXISTS idx_source_artifacts_ext_uid
    ON staging.source_artifacts (ext_uid);
CREATE INDEX IF NOT EXISTS idx_source_artifacts_status
    ON staging.source_artifacts (status);
CREATE INDEX IF NOT EXISTS idx_source_artifacts_last_seen_scan_run_id
    ON staging.source_artifacts (last_seen_scan_run_id);

-- ------------------------------------------------------------
-- 13. artifact_batches
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.artifact_batches (
    id                      bigserial PRIMARY KEY,
    artifact_uid            text NOT NULL,
    artifact_type           varchar(100) NOT NULL,
    run_id                  bigint,
    raw_data_ref_id         bigint,
    bi_steps_count          integer DEFAULT 0,
    interfaces_count        integer DEFAULT 0,
    operations_count        integer DEFAULT 0,
    products_count          integer DEFAULT 0,
    containers_count        integer DEFAULT 0,
    is_current              boolean DEFAULT true,
    created_at              timestamp DEFAULT now(),
    CONSTRAINT fk_artifact_batches_run_id
        FOREIGN KEY (run_id) REFERENCES staging.pipeline_runs (id),
    CONSTRAINT fk_artifact_batches_raw_data_ref_id
        FOREIGN KEY (raw_data_ref_id) REFERENCES staging.raw_data_refs (id)
);
COMMENT ON TABLE staging.artifact_batches IS 'Группировка артефактов для итеративного сохранения (E2E только)';
COMMENT ON COLUMN staging.artifact_batches.id IS 'Идентификатор батча артефактов';
COMMENT ON COLUMN staging.artifact_batches.artifact_uid IS 'UID артефакта';
COMMENT ON COLUMN staging.artifact_batches.artifact_type IS 'Тип артефакта';
COMMENT ON COLUMN staging.artifact_batches.run_id IS 'Запуск пайплайна';
COMMENT ON COLUMN staging.artifact_batches.raw_data_ref_id IS 'Сырые данные';
COMMENT ON COLUMN staging.artifact_batches.bi_steps_count IS 'Количество BI шагов';
COMMENT ON COLUMN staging.artifact_batches.interfaces_count IS 'Количество интерфейсов';
COMMENT ON COLUMN staging.artifact_batches.operations_count IS 'Количество операций';
COMMENT ON COLUMN staging.artifact_batches.products_count IS 'Количество продуктов';
COMMENT ON COLUMN staging.artifact_batches.containers_count IS 'Количество контейнеров';
COMMENT ON COLUMN staging.artifact_batches.is_current IS 'Флаг актуальности';
COMMENT ON COLUMN staging.artifact_batches.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_artifact_batches_artifact_uid_type
    ON staging.artifact_batches (artifact_uid, artifact_type);
CREATE INDEX IF NOT EXISTS idx_artifact_batches_run_id
    ON staging.artifact_batches (run_id);

-- ------------------------------------------------------------
-- 14. pipeline_stage_logs
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.pipeline_stage_logs (
    id                  bigserial PRIMARY KEY,
    run_id              bigint NOT NULL,
    scan_run_id         bigint NOT NULL,
    stage_name          varchar(50) NOT NULL,
    status              varchar(20) NOT NULL DEFAULT 'running',
    input_data          text,
    output_data         text,
    summary_json        jsonb,
    started_at          timestamp NOT NULL DEFAULT now(),
    completed_at        timestamp,
    failure_reason      text,
    CONSTRAINT fk_pipeline_stage_logs_run_id
        FOREIGN KEY (run_id) REFERENCES staging.pipeline_runs (id),
    CONSTRAINT fk_pipeline_stage_logs_scan_run_id
        FOREIGN KEY (scan_run_id) REFERENCES staging.pipeline_runs (id)
);
COMMENT ON TABLE staging.pipeline_stage_logs IS 'Детальные логи выполнения стадий пайплайна';
COMMENT ON COLUMN staging.pipeline_stage_logs.id IS 'Идентификатор лога стадии';
COMMENT ON COLUMN staging.pipeline_stage_logs.run_id IS 'Запуск пайплайна';
COMMENT ON COLUMN staging.pipeline_stage_logs.scan_run_id IS 'Скан источника';
COMMENT ON COLUMN staging.pipeline_stage_logs.stage_name IS 'Стадия: pre_adapter, adapter, validator, transformer, saver';
COMMENT ON COLUMN staging.pipeline_stage_logs.status IS 'Статус: running, completed, failed, skipped';
COMMENT ON COLUMN staging.pipeline_stage_logs.input_data IS 'Входные данные (идентификатор)';
COMMENT ON COLUMN staging.pipeline_stage_logs.output_data IS 'Выходные данные (идентификатор/результат)';
COMMENT ON COLUMN staging.pipeline_stage_logs.summary_json IS 'Лёгкие метрики для дашбордов';
COMMENT ON COLUMN staging.pipeline_stage_logs.started_at IS 'Дата и время начала';
COMMENT ON COLUMN staging.pipeline_stage_logs.completed_at IS 'Дата и время завершения';
COMMENT ON COLUMN staging.pipeline_stage_logs.failure_reason IS 'Причина ошибки';

CREATE INDEX IF NOT EXISTS idx_pipeline_stage_logs_run_id
    ON staging.pipeline_stage_logs (run_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_stage_logs_scan_run_id
    ON staging.pipeline_stage_logs (scan_run_id);

-- ------------------------------------------------------------
-- 15. artifact_notices
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.artifact_notices (
    id                      bigserial PRIMARY KEY,
    notice_type_id          bigint NOT NULL,
    raw_data_context_id     bigint NOT NULL,
    details                 text,
    created_at              timestamp DEFAULT now(),
    CONSTRAINT fk_artifact_notices_notice_type_id
        FOREIGN KEY (notice_type_id) REFERENCES staging.notice_types (id),
    CONSTRAINT fk_artifact_notices_raw_data_context_id
        FOREIGN KEY (raw_data_context_id) REFERENCES staging.raw_data_contexts (id)
);
COMMENT ON TABLE staging.artifact_notices IS 'Журнал замечаний валидации, трансформации и match-решений';
COMMENT ON COLUMN staging.artifact_notices.id IS 'Идентификатор замечания';
COMMENT ON COLUMN staging.artifact_notices.notice_type_id IS 'Ссылка на тип замечания';
COMMENT ON COLUMN staging.artifact_notices.raw_data_context_id IS 'Ссылка на контекст сырых данных';
COMMENT ON COLUMN staging.artifact_notices.details IS 'JSON-строка: структурированные данные замечания';
COMMENT ON COLUMN staging.artifact_notices.created_at IS 'Дата создания замечания';

CREATE INDEX IF NOT EXISTS idx_artifact_notices_raw_data_context_id
    ON staging.artifact_notices (raw_data_context_id);
CREATE INDEX IF NOT EXISTS idx_artifact_notices_notice_type_id
    ON staging.artifact_notices (notice_type_id);
CREATE INDEX IF NOT EXISTS idx_artifact_notices_created_at
    ON staging.artifact_notices (created_at);
