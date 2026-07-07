-- ===================================================================
-- V0008: Notice index, raw_data_context, products, containers
-- ===================================================================
-- Дата: 2026-07-07
-- Описание:
--   1. Добавить недостающий индекс на artifact_notices(created_at DESC)
--   2. Создать таблицу raw_data_context (ADR-005)
--   3. Создать сущность products + product_versions с external_guid
--   4. Создать сущность containers + container_versions с external_guid
--   5. Добавить interface_versions.container_version_id FK
--   6. Добавить tc.product_id FK → products.id
--   7. Добавить счётчики products_count, containers_count в artifact_batches
-- ===================================================================

-- ===================================================================
-- 1. Notice-инфраструктура: недостающий индекс (ADR-009)
-- ===================================================================

CREATE INDEX idx_artifact_notices_created_at_desc
    ON staging.artifact_notices (created_at DESC);

COMMENT ON INDEX idx_artifact_notices_created_at_desc IS
    'Индекс для сортировки замечаний по дате (новые сверху) — ADR-009';

-- ===================================================================
-- 2. Raw Data Context Navigation (ADR-005)
-- ===================================================================

CREATE TABLE staging.raw_data_context (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_data_ref_id  bigint       NOT NULL REFERENCES staging.raw_data_refs(id),
    format           varchar(50)  NOT NULL CHECK (format IN ('json', 'yaml', 'xml', 'text', 'binary', 'excel', 'word')),
    navigation_type  varchar(50)  NOT NULL CHECK (navigation_type IN ('json_path', 'yaml_path', 'xml_xpath', 'line_range', 'byte_range')),
    position         jsonb        NOT NULL,
    created_at       timestamp    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE staging.raw_data_context IS
    'Контекст навигации в сырых данных — ADR-005';

COMMENT ON COLUMN staging.raw_data_context.id IS 'Уникальный идентификатор записи контекста';
COMMENT ON COLUMN staging.raw_data_context.raw_data_ref_id IS 'Сырые данные из raw_data_refs';
COMMENT ON COLUMN staging.raw_data_context.format IS 'Формат данных: json, yaml, xml, text, binary, excel, word';
COMMENT ON COLUMN staging.raw_data_context.navigation_type IS 'Тип навигации: json_path, yaml_path, xml_xpath, line_range, byte_range';
COMMENT ON COLUMN staging.raw_data_context.position IS 'Значение навигации (path, line_range, byte_range)';
COMMENT ON COLUMN staging.raw_data_context.created_at IS 'Дата создания';

CREATE INDEX idx_raw_data_context_ref_id ON staging.raw_data_context (raw_data_ref_id);
CREATE INDEX idx_raw_data_context_nav_type ON staging.raw_data_context (navigation_type);

COMMENT ON INDEX idx_raw_data_context_ref_id IS 'Быстрый поиск по сырым данным';
COMMENT ON INDEX idx_raw_data_context_nav_type IS 'Фильтрация по типу навигации';

-- ===================================================================
-- 3. Products: identity + versions (external_guid)
-- ===================================================================

CREATE TABLE staging.products (
    id         integer PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    uid        varchar(100) UNIQUE NOT NULL,
    created_at timestamp NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE staging.products IS
    'Канонический справочник продуктов BeeAtlas (identity)';

COMMENT ON COLUMN staging.products.id IS 'Идентификатор продукта';
COMMENT ON COLUMN staging.products.uid IS 'Уникальный идентификатор продукта';
COMMENT ON COLUMN staging.products.created_at IS 'Дата и время создания';

CREATE TABLE staging.product_versions (
    id                integer PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    product_id        integer NOT NULL REFERENCES staging.products(id),
    name              text,
    description       text,
    external_guid     varchar(100),
    raw_data_ref_id   bigint  REFERENCES staging.raw_data_refs(id),
    batch_id          bigint  REFERENCES staging.artifact_batches(id),
    context           text,
    match_notice_id   bigint  REFERENCES staging.artifact_notices(id),
    created_at        timestamp NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE staging.product_versions IS
    'Версионированные снимки продукта с provenance и внешним GUID';

COMMENT ON COLUMN staging.product_versions.id IS 'Идентификатор версии';
COMMENT ON COLUMN staging.product_versions.product_id IS 'Продукт в справочнике';
COMMENT ON COLUMN staging.product_versions.name IS 'Наименование продукта';
COMMENT ON COLUMN staging.product_versions.description IS 'Описание продукта';
COMMENT ON COLUMN staging.product_versions.external_guid IS 'Внешний GUID продукта';
COMMENT ON COLUMN staging.product_versions.raw_data_ref_id IS 'Источник сырых данных';
COMMENT ON COLUMN staging.product_versions.batch_id IS 'Батч артефактов';
COMMENT ON COLUMN staging.product_versions.context IS 'Контекст данных';
COMMENT ON COLUMN staging.product_versions.match_notice_id IS 'Match-замечание: причина привязки к сущности';
COMMENT ON COLUMN staging.product_versions.created_at IS 'Дата и время создания';

CREATE INDEX idx_product_versions_batch_id ON staging.product_versions (batch_id);
CREATE INDEX idx_product_versions_match_notice ON staging.product_versions (match_notice_id);
CREATE INDEX idx_product_versions_product_id ON staging.product_versions (product_id);

COMMENT ON INDEX idx_product_versions_batch_id IS 'Быстрый поиск по батчу';
COMMENT ON INDEX idx_product_versions_match_notice IS 'Быстрый поиск истории привязок';
COMMENT ON INDEX idx_product_versions_product_id IS 'Версии продукта по ID';

-- ===================================================================
-- 4. Containers: identity + versions (external_guid, product_version_id)
-- ===================================================================

CREATE TABLE staging.containers (
    id         integer PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    uid        varchar(100) UNIQUE NOT NULL,
    created_at timestamp NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE staging.containers IS
    'Канонический справочник контейнеров (identity) — микросервис/БД/web-app';

COMMENT ON COLUMN staging.containers.id IS 'Идентификатор контейнера';
COMMENT ON COLUMN staging.containers.uid IS 'Уникальный идентификатор контейнера';
COMMENT ON COLUMN staging.containers.created_at IS 'Дата и время создания';

CREATE TABLE staging.container_versions (
    id                  integer PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    container_id        integer NOT NULL REFERENCES staging.containers(id),
    product_version_id  integer REFERENCES staging.product_versions(id),
    name                text,
    description         text,
    technology          text,
    external_guid       varchar(100),
    raw_data_ref_id     bigint  REFERENCES staging.raw_data_refs(id),
    batch_id            bigint  REFERENCES staging.artifact_batches(id),
    context             text,
    match_notice_id     bigint  REFERENCES staging.artifact_notices(id),
    created_at          timestamp NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE staging.container_versions IS
    'Версионированные снимки контейнера с provenance, внешним GUID и связью с версией продукта';

COMMENT ON COLUMN staging.container_versions.id IS 'Идентификатор версии';
COMMENT ON COLUMN staging.container_versions.container_id IS 'Контейнер в справочнике';
COMMENT ON COLUMN staging.container_versions.product_version_id IS 'Версия продукта-владельца (1:N)';
COMMENT ON COLUMN staging.container_versions.name IS 'Наименование контейнера';
COMMENT ON COLUMN staging.container_versions.description IS 'Описание контейнера';
COMMENT ON COLUMN staging.container_versions.technology IS 'Технология контейнера';
COMMENT ON COLUMN staging.container_versions.external_guid IS 'Внешний GUID контейнера';
COMMENT ON COLUMN staging.container_versions.raw_data_ref_id IS 'Источник сырых данных';
COMMENT ON COLUMN staging.container_versions.batch_id IS 'Батч артефактов';
COMMENT ON COLUMN staging.container_versions.context IS 'Контекст данных';
COMMENT ON COLUMN staging.container_versions.match_notice_id IS 'Match-замечание: причина привязки к сущности';
COMMENT ON COLUMN staging.container_versions.created_at IS 'Дата и время создания';

CREATE INDEX idx_container_versions_batch_id ON staging.container_versions (batch_id);
CREATE INDEX idx_container_versions_match_notice ON staging.container_versions (match_notice_id);
CREATE INDEX idx_container_versions_product_version_id ON staging.container_versions (product_version_id);
CREATE INDEX idx_container_versions_container_id ON staging.container_versions (container_id);

COMMENT ON INDEX idx_container_versions_batch_id IS 'Быстрый поиск по батчу';
COMMENT ON INDEX idx_container_versions_match_notice IS 'Быстрый поиск истории привязок';
COMMENT ON INDEX idx_container_versions_product_version_id IS 'Версии контейнеров по версии продукта';
COMMENT ON INDEX idx_container_versions_container_id IS 'Версии контейнера по ID';

-- ===================================================================
-- 5. Связь контейнера с интерфейсами
-- ===================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging'
          AND table_name = 'interface_versions'
          AND column_name = 'container_version_id'
    ) THEN
        ALTER TABLE staging.interface_versions
            ADD COLUMN container_version_id integer REFERENCES staging.container_versions(id);
    END IF;
END $$;

COMMENT ON COLUMN staging.interface_versions.container_version_id IS
    'Контейнер-владелец интерфейса (1:N)';

-- ===================================================================
-- 6. Привязка tc.product_id FK → products.id
-- ===================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = 'staging'
          AND table_name = 'tc'
          AND constraint_type = 'FOREIGN KEY'
          AND constraint_name = 'fk_tc_product'
    ) THEN
        ALTER TABLE staging.tc
            ADD CONSTRAINT fk_tc_product
            FOREIGN KEY (product_id) REFERENCES staging.products(id);
    END IF;
END $$;

COMMENT ON CONSTRAINT fk_tc_product ON staging.tc IS
    'Связь TC с продуктом — tc.product_id → products.id';

-- ===================================================================
-- 7. artifact_batches: счётчики products и containers
-- ===================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging'
          AND table_name = 'artifact_batches'
          AND column_name = 'products_count'
    ) THEN
        ALTER TABLE staging.artifact_batches
            ADD COLUMN products_count integer NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging'
          AND table_name = 'artifact_batches'
          AND column_name = 'containers_count'
    ) THEN
        ALTER TABLE staging.artifact_batches
            ADD COLUMN containers_count integer NOT NULL DEFAULT 0;
    END IF;
END $$;

COMMENT ON COLUMN staging.artifact_batches.products_count IS
    'Количество продуктов в батче';
COMMENT ON COLUMN staging.artifact_batches.containers_count IS
    'Количество контейнеров в батче';
