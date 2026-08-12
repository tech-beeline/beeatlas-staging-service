-- ============================================================
-- V0008: Add json_data to version tables (BLG-004 / ADR-011)
-- ============================================================
-- Целевая схема: версии канонических сущностей (*_versions) хранят
-- только ОСНОВНЫЕ поля отдельными колонками (id, <entity>_id, ext_uid,
-- name, служебные raw_data_context_id/match_notice_id/created_at,
-- ссылки на версии родительских сущностей); НЕосновные атрибуты
-- переносятся в jsonb-колонку json_data (BR-15, BR-16, FR-003-20).
--
-- Стратегия миграции (ADR-011, вариант 3 «перенос + очистка колонок»,
-- FR-003-23/BR-17, разр. дублей EC-008):
--   1. ADD COLUMN IF NOT EXISTS json_data jsonb  (nullable, без DEFAULT);
--   2. UPDATE ... SET json_data = jsonb_strip_nulls(jsonb_build_object(...))
--      — сбор объекта из старых колонок, NULL-ключи исключаются
--        (отсутствие ключа = отсутствие значения, FR-003-22/BR-18);
--   3. обнуление старых колонок (UPDATE SET <attr> = NULL) + DROP COLUMN;
--   4. ADD CONSTRAINT CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object')
--      (FR-003-24 на уровне БД);
--   5. COMMENT ON COLUMN — документация.
-- Одна транзакция на всю миграцию (Flyway default): сбой → откат всех
-- 13 таблиц, потери данных исключены (BR-17).
--
-- Идемпотентность: ADD COLUMN IF NOT EXISTS / DROP COLUMN IF EXISTS;
-- UPDATE и CHECK-constraint обёрнуты в DO-блоки с проверкой текущего
-- состояния схемы (безопасно для повторного запуска, не в ущерб
-- читаемости — по одному DO-блоку на таблицу).
--
-- Замечание о DROP NOT NULL: ни одна из переносимых колонок в V0003
-- не имеет NOT NULL (NOT NULL держат только name в operation_versions и
-- related_operation_version_id в operation_relation_versions — они
-- остаются колонками), поэтому секция «ALTER COLUMN ... DROP NOT NULL»
-- не требуется; для полноты стратегии колонки обнуляются перед DROP.
-- ============================================================

-- ------------------------------------------------------------
-- 1. bi_step_versions
-- ------------------------------------------------------------
ALTER TABLE staging.bi_step_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'bi_step_versions'
                 AND column_name = 'rps') THEN
        UPDATE staging.bi_step_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'rps', rps, 'latency', latency, 'error_rate', error_rate, 'source_id', source_id)),
            rps = NULL, latency = NULL, error_rate = NULL, source_id = NULL;
    END IF;
END $$;

ALTER TABLE staging.bi_step_versions DROP COLUMN IF EXISTS rps;
ALTER TABLE staging.bi_step_versions DROP COLUMN IF EXISTS latency;
ALTER TABLE staging.bi_step_versions DROP COLUMN IF EXISTS error_rate;
ALTER TABLE staging.bi_step_versions DROP COLUMN IF EXISTS source_id;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_bi_step_versions_json_data_object') THEN
        ALTER TABLE staging.bi_step_versions
            ADD CONSTRAINT ck_bi_step_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.bi_step_versions.json_data IS
    'Неосновные атрибуты BI шага (перенесены из rps/latency/error_rate/source_id, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 2. tech_capability_versions
-- ------------------------------------------------------------
ALTER TABLE staging.tech_capability_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'tech_capability_versions'
                 AND column_name = 'description') THEN
        UPDATE staging.tech_capability_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object('description', description)),
            description = NULL;
    END IF;
END $$;

ALTER TABLE staging.tech_capability_versions DROP COLUMN IF EXISTS description;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_tech_capability_versions_json_data_object') THEN
        ALTER TABLE staging.tech_capability_versions
            ADD CONSTRAINT ck_tech_capability_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.tech_capability_versions.json_data IS
    'Неосновные атрибуты ТС (перенесены из description, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 3. product_versions
-- ------------------------------------------------------------
ALTER TABLE staging.product_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'product_versions'
                 AND column_name = 'description') THEN
        UPDATE staging.product_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'description', description, 'author', author)),
            description = NULL, author = NULL;
    END IF;
END $$;

ALTER TABLE staging.product_versions DROP COLUMN IF EXISTS description;
ALTER TABLE staging.product_versions DROP COLUMN IF EXISTS author;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_product_versions_json_data_object') THEN
        ALTER TABLE staging.product_versions
            ADD CONSTRAINT ck_product_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.product_versions.json_data IS
    'Неосновные атрибуты продукта (перенесены из description/author, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 4. container_versions
-- ------------------------------------------------------------
ALTER TABLE staging.container_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'container_versions'
                 AND column_name = 'version') THEN
        UPDATE staging.container_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'version', version, 'description', description, 'technology', technology)),
            version = NULL, description = NULL, technology = NULL;
    END IF;
END $$;

ALTER TABLE staging.container_versions DROP COLUMN IF EXISTS version;
ALTER TABLE staging.container_versions DROP COLUMN IF EXISTS description;
ALTER TABLE staging.container_versions DROP COLUMN IF EXISTS technology;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_container_versions_json_data_object') THEN
        ALTER TABLE staging.container_versions
            ADD CONSTRAINT ck_container_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.container_versions.json_data IS
    'Неосновные атрибуты контейнера (перенесены из version/description/technology, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 5. interface_versions
-- ------------------------------------------------------------
ALTER TABLE staging.interface_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'interface_versions'
                 AND column_name = 'protocol') THEN
        UPDATE staging.interface_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'protocol', protocol, 'spec_link', spec_link, 'version', version,
                'description', description, 'source_metric', source_metric)),
            protocol = NULL, spec_link = NULL, version = NULL,
            description = NULL, source_metric = NULL;
    END IF;
END $$;

ALTER TABLE staging.interface_versions DROP COLUMN IF EXISTS protocol;
ALTER TABLE staging.interface_versions DROP COLUMN IF EXISTS spec_link;
ALTER TABLE staging.interface_versions DROP COLUMN IF EXISTS version;
ALTER TABLE staging.interface_versions DROP COLUMN IF EXISTS description;
ALTER TABLE staging.interface_versions DROP COLUMN IF EXISTS source_metric;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_interface_versions_json_data_object') THEN
        ALTER TABLE staging.interface_versions
            ADD CONSTRAINT ck_interface_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.interface_versions.json_data IS
    'Неосновные атрибуты интерфейса (перенесены из protocol/spec_link/version/description/source_metric, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 6. operation_versions
-- ------------------------------------------------------------
ALTER TABLE staging.operation_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'operation_versions'
                 AND column_name = 'type') THEN
        UPDATE staging.operation_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'type', type, 'rps', rps, 'latency', latency, 'error_rate', error_rate,
                'description', description, 'return_type', return_type)),
            type = NULL, rps = NULL, latency = NULL, error_rate = NULL,
            description = NULL, return_type = NULL;
    END IF;
END $$;

ALTER TABLE staging.operation_versions DROP COLUMN IF EXISTS type;
ALTER TABLE staging.operation_versions DROP COLUMN IF EXISTS rps;
ALTER TABLE staging.operation_versions DROP COLUMN IF EXISTS latency;
ALTER TABLE staging.operation_versions DROP COLUMN IF EXISTS error_rate;
ALTER TABLE staging.operation_versions DROP COLUMN IF EXISTS description;
ALTER TABLE staging.operation_versions DROP COLUMN IF EXISTS return_type;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_operation_versions_json_data_object') THEN
        ALTER TABLE staging.operation_versions
            ADD CONSTRAINT ck_operation_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.operation_versions.json_data IS
    'Неосновные атрибуты операции (перенесены из type/rps/latency/error_rate/description/return_type, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 7. sequence_versions
-- ------------------------------------------------------------
ALTER TABLE staging.sequence_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'sequence_versions'
                 AND column_name = 'description') THEN
        UPDATE staging.sequence_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object('description', description)),
            description = NULL;
    END IF;
END $$;

ALTER TABLE staging.sequence_versions DROP COLUMN IF EXISTS description;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_sequence_versions_json_data_object') THEN
        ALTER TABLE staging.sequence_versions
            ADD CONSTRAINT ck_sequence_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.sequence_versions.json_data IS
    'Неосновные атрибуты Sequence (перенесены из description, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 8. e2e_scenario_versions
-- ------------------------------------------------------------
ALTER TABLE staging.e2e_scenario_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'e2e_scenario_versions'
                 AND column_name = 'description') THEN
        UPDATE staging.e2e_scenario_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object('description', description)),
            description = NULL;
    END IF;
END $$;

ALTER TABLE staging.e2e_scenario_versions DROP COLUMN IF EXISTS description;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_e2e_scenario_versions_json_data_object') THEN
        ALTER TABLE staging.e2e_scenario_versions
            ADD CONSTRAINT ck_e2e_scenario_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.e2e_scenario_versions.json_data IS
    'Неосновные атрибуты e2e-сценария (перенесены из description, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 9. cj_versions
-- (Java-моделей нет (cx-backend не интегрирован, OQ-01) — данные
-- переносятся по правилу BR-17; модели/саверы появятся при интеграции
-- cx-backend, вне BLG-004/ADR-011)
-- ------------------------------------------------------------
ALTER TABLE staging.cj_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'cj_versions'
                 AND column_name = 'user_portrait') THEN
        UPDATE staging.cj_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'user_portrait', user_portrait, 'draft', draft, 'id_author', id_author,
                'id_product_ext', id_product_ext, 'unique_ident', unique_ident,
                'dashboard_link', dashboard_link, 'bpmn', bpmn,
                'id_business_owner', id_business_owner)),
            user_portrait = NULL, draft = NULL, id_author = NULL, id_product_ext = NULL,
            unique_ident = NULL, dashboard_link = NULL, bpmn = NULL, id_business_owner = NULL;
    END IF;
END $$;

ALTER TABLE staging.cj_versions DROP COLUMN IF EXISTS user_portrait;
ALTER TABLE staging.cj_versions DROP COLUMN IF EXISTS draft;
ALTER TABLE staging.cj_versions DROP COLUMN IF EXISTS id_author;
ALTER TABLE staging.cj_versions DROP COLUMN IF EXISTS id_product_ext;
ALTER TABLE staging.cj_versions DROP COLUMN IF EXISTS unique_ident;
ALTER TABLE staging.cj_versions DROP COLUMN IF EXISTS dashboard_link;
ALTER TABLE staging.cj_versions DROP COLUMN IF EXISTS bpmn;
ALTER TABLE staging.cj_versions DROP COLUMN IF EXISTS id_business_owner;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_cj_versions_json_data_object') THEN
        ALTER TABLE staging.cj_versions
            ADD CONSTRAINT ck_cj_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.cj_versions.json_data IS
    'Неосновные атрибуты CJ (перенесены из user_portrait/draft/id_author/id_product_ext/unique_ident/dashboard_link/bpmn/id_business_owner, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 10. cj_step_versions
-- (Java-моделей нет (cx-backend не интегрирован, OQ-01) — данные
-- переносятся по правилу BR-17)
-- ------------------------------------------------------------
ALTER TABLE staging.cj_step_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'cj_step_versions'
                 AND column_name = 'step_order') THEN
        UPDATE staging.cj_step_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'step_order', step_order, 'description', description)),
            step_order = NULL, description = NULL;
    END IF;
END $$;

ALTER TABLE staging.cj_step_versions DROP COLUMN IF EXISTS step_order;
ALTER TABLE staging.cj_step_versions DROP COLUMN IF EXISTS description;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_cj_step_versions_json_data_object') THEN
        ALTER TABLE staging.cj_step_versions
            ADD CONSTRAINT ck_cj_step_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.cj_step_versions.json_data IS
    'Неосновные атрибуты шага CJ (перенесены из step_order/description, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 11. bi_step_relation_versions
-- ------------------------------------------------------------
ALTER TABLE staging.bi_step_relation_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'bi_step_relation_versions'
                 AND column_name = 'call_order') THEN
        UPDATE staging.bi_step_relation_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'call_order', call_order, 'stereotype', stereotype)),
            call_order = NULL, stereotype = NULL;
    END IF;
END $$;

ALTER TABLE staging.bi_step_relation_versions DROP COLUMN IF EXISTS call_order;
ALTER TABLE staging.bi_step_relation_versions DROP COLUMN IF EXISTS stereotype;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_bi_step_relation_versions_json_data_object') THEN
        ALTER TABLE staging.bi_step_relation_versions
            ADD CONSTRAINT ck_bi_step_relation_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.bi_step_relation_versions.json_data IS
    'Неосновные атрибуты связи BI-шаг↔операция (перенесены из call_order/stereotype, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 12. operation_relation_versions
-- ------------------------------------------------------------
ALTER TABLE staging.operation_relation_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'operation_relation_versions'
                 AND column_name = 'call_order') THEN
        UPDATE staging.operation_relation_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'call_order', call_order, 'stereotype', stereotype)),
            call_order = NULL, stereotype = NULL;
    END IF;
END $$;

ALTER TABLE staging.operation_relation_versions DROP COLUMN IF EXISTS call_order;
ALTER TABLE staging.operation_relation_versions DROP COLUMN IF EXISTS stereotype;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_operation_relation_versions_json_data_object') THEN
        ALTER TABLE staging.operation_relation_versions
            ADD CONSTRAINT ck_operation_relation_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.operation_relation_versions.json_data IS
    'Неосновные атрибуты связи операция↔операция (перенесены из call_order/stereotype, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';

-- ------------------------------------------------------------
-- 13. sequence_relation_versions
-- ------------------------------------------------------------
ALTER TABLE staging.sequence_relation_versions
    ADD COLUMN IF NOT EXISTS json_data jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'staging' AND table_name = 'sequence_relation_versions'
                 AND column_name = 'call_order') THEN
        UPDATE staging.sequence_relation_versions
        SET json_data = jsonb_strip_nulls(jsonb_build_object(
                'call_order', call_order, 'stereotype', stereotype)),
            call_order = NULL, stereotype = NULL;
    END IF;
END $$;

ALTER TABLE staging.sequence_relation_versions DROP COLUMN IF EXISTS call_order;
ALTER TABLE staging.sequence_relation_versions DROP COLUMN IF EXISTS stereotype;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_sequence_relation_versions_json_data_object') THEN
        ALTER TABLE staging.sequence_relation_versions
            ADD CONSTRAINT ck_sequence_relation_versions_json_data_object
            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN staging.sequence_relation_versions.json_data IS
    'Неосновные атрибуты связи Sequence↔операция (перенесены из call_order/stereotype, ADR-011): ключи snake_case, NULL/{} = атрибуты отсутствуют (FR-003-22)';