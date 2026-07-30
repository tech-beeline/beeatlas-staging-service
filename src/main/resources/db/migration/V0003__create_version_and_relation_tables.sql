-- ============================================================
-- V0003: Create version and relation tables
-- Версии сущностей + связи между версиями
-- ============================================================

-- ============================================================
-- VERSION TABLES
-- ============================================================

-- ------------------------------------------------------------
-- 1. bi_step_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.bi_step_versions (
    id                      bigserial PRIMARY KEY,
    bi_step_id              bigint REFERENCES staging.bi_steps (id),
    ext_uid                 text,
    name                    text,
    rps                     numeric,
    latency                 numeric,
    error_rate              numeric,
    source_id               varchar(100),
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.bi_step_versions IS 'Версионированные данные BI шагов';
COMMENT ON COLUMN staging.bi_step_versions.id IS 'Идентификатор версии BI шага';
COMMENT ON COLUMN staging.bi_step_versions.bi_step_id IS 'BI шаг в справочнике';
COMMENT ON COLUMN staging.bi_step_versions.ext_uid IS 'Внешний идентификатор BI шага в источнике';
COMMENT ON COLUMN staging.bi_step_versions.name IS 'Название BI шага';
COMMENT ON COLUMN staging.bi_step_versions.rps IS 'Нагрузка, requests/sec';
COMMENT ON COLUMN staging.bi_step_versions.latency IS 'Время выполнения, ms';
COMMENT ON COLUMN staging.bi_step_versions.error_rate IS 'Процент ошибок, %';
COMMENT ON COLUMN staging.bi_step_versions.source_id IS 'Идентификатор источника';
COMMENT ON COLUMN staging.bi_step_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.bi_step_versions.match_notice_id IS 'Match-замечание: причина привязки к сущности';
COMMENT ON COLUMN staging.bi_step_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_bi_step_versions_match_notice_id
    ON staging.bi_step_versions (match_notice_id);

-- ------------------------------------------------------------
-- 2. tech_capability_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.tech_capability_versions (
    id                      bigserial PRIMARY KEY,
    tech_capability_id      bigint REFERENCES staging.tech_capabilities (id),
    ext_uid                 text,
    name                    text,
    description             text,
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.tech_capability_versions IS 'Версионированные данные ТС';
COMMENT ON COLUMN staging.tech_capability_versions.id IS 'Идентификатор версии ТС';
COMMENT ON COLUMN staging.tech_capability_versions.tech_capability_id IS 'ТС в справочнике';
COMMENT ON COLUMN staging.tech_capability_versions.ext_uid IS 'Внешний идентификатор ТС в источнике';
COMMENT ON COLUMN staging.tech_capability_versions.name IS 'Название ТС';
COMMENT ON COLUMN staging.tech_capability_versions.description IS 'Описание ТС';
COMMENT ON COLUMN staging.tech_capability_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.tech_capability_versions.match_notice_id IS 'Match-замечание: причина привязки к сущности';
COMMENT ON COLUMN staging.tech_capability_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_tech_capability_versions_match_notice_id
    ON staging.tech_capability_versions (match_notice_id);

-- ------------------------------------------------------------
-- 3. product_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.product_versions (
    id                      bigserial PRIMARY KEY,
    product_id              bigint REFERENCES staging.products (id),
    ext_uid                 text,
    name                    text,
    description             text,
    author                  varchar(255),
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.product_versions IS 'Версионированные снимки продукта с provenance и внешним GUID';
COMMENT ON COLUMN staging.product_versions.id IS 'Идентификатор версии продукта';
COMMENT ON COLUMN staging.product_versions.product_id IS 'Продукт в справочнике';
COMMENT ON COLUMN staging.product_versions.ext_uid IS 'Внешний GUID продукта';
COMMENT ON COLUMN staging.product_versions.name IS 'Наименование продукта';
COMMENT ON COLUMN staging.product_versions.description IS 'Описание продукта';
COMMENT ON COLUMN staging.product_versions.author IS 'Автор продукта (из properties.author softwareSystem)';
COMMENT ON COLUMN staging.product_versions.raw_data_context_id IS 'Источник сырых данных';
COMMENT ON COLUMN staging.product_versions.match_notice_id IS 'Match-замечание';
COMMENT ON COLUMN staging.product_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_product_versions_match_notice_id
    ON staging.product_versions (match_notice_id);
CREATE INDEX IF NOT EXISTS idx_product_versions_product_id
    ON staging.product_versions (product_id);

-- ------------------------------------------------------------
-- 4. container_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.container_versions (
    id                      bigserial PRIMARY KEY,
    container_id            bigint REFERENCES staging.containers (id),
    product_version_id      bigint REFERENCES staging.product_versions (id),
    ext_uid                 text,
    name                    text,
    version                 text,
    description             text,
    technology              text,
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.container_versions IS 'Версионированные снимки контейнера с provenance, внешним GUID и связью с версией продукта';
COMMENT ON COLUMN staging.container_versions.id IS 'Идентификатор версии контейнера';
COMMENT ON COLUMN staging.container_versions.container_id IS 'Контейнер в справочнике';
COMMENT ON COLUMN staging.container_versions.product_version_id IS 'Версия продукта-владельца (1:N)';
COMMENT ON COLUMN staging.container_versions.ext_uid IS 'Внешний GUID контейнера';
COMMENT ON COLUMN staging.container_versions.name IS 'Наименование контейнера';
COMMENT ON COLUMN staging.container_versions.version IS 'Версия контейнера из fdm-products';
COMMENT ON COLUMN staging.container_versions.description IS 'Описание контейнера';
COMMENT ON COLUMN staging.container_versions.technology IS 'Технология контейнера';
COMMENT ON COLUMN staging.container_versions.raw_data_context_id IS 'Источник сырых данных';
COMMENT ON COLUMN staging.container_versions.match_notice_id IS 'Match-замечание';
COMMENT ON COLUMN staging.container_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_container_versions_match_notice_id
    ON staging.container_versions (match_notice_id);
CREATE INDEX IF NOT EXISTS idx_container_versions_product_version_id
    ON staging.container_versions (product_version_id);
CREATE INDEX IF NOT EXISTS idx_container_versions_container_id
    ON staging.container_versions (container_id);

-- ------------------------------------------------------------
-- 5. interface_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.interface_versions (
    id                      bigserial PRIMARY KEY,
    interface_id            bigint REFERENCES staging.interfaces (id),
    ext_uid                 text,
    protocol                text,
    name                    text,
    spec_link               text,
    version                 text,
    description             text,
    source_metric           text,
    container_version_id    bigint REFERENCES staging.container_versions (id),
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.interface_versions IS 'Версионированные данные интерфейсов';
COMMENT ON COLUMN staging.interface_versions.id IS 'Идентификатор версии интерфейса';
COMMENT ON COLUMN staging.interface_versions.interface_id IS 'Интерфейс в справочнике';
COMMENT ON COLUMN staging.interface_versions.ext_uid IS 'Идентификатор интерфейса в источнике';
COMMENT ON COLUMN staging.interface_versions.protocol IS 'Протокол доступа';
COMMENT ON COLUMN staging.interface_versions.name IS 'Наименование интерфейса из product.interface (fdm-products)';
COMMENT ON COLUMN staging.interface_versions.spec_link IS 'Ссылка на спецификацию интерфейса из product.interface (fdm-products)';
COMMENT ON COLUMN staging.interface_versions.version IS 'Версия интерфейса из product.interface (fdm-products)';
COMMENT ON COLUMN staging.interface_versions.description IS 'Описание интерфейса из product.interface (fdm-products)';
COMMENT ON COLUMN staging.interface_versions.source_metric IS 'Источник метрики из product.interface (fdm-products)';
COMMENT ON COLUMN staging.interface_versions.container_version_id IS 'Контейнер-владелец интерфейса (1:N)';
COMMENT ON COLUMN staging.interface_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.interface_versions.match_notice_id IS 'Match-замечание: причина привязки к сущности';
COMMENT ON COLUMN staging.interface_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_interface_versions_match_notice_id
    ON staging.interface_versions (match_notice_id);

-- ------------------------------------------------------------
-- 6. operation_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.operation_versions (
    id                      bigserial PRIMARY KEY,
    operation_id            bigint REFERENCES staging.operations (id),
    interface_version_id    bigint REFERENCES staging.interface_versions (id),
    ext_uid                 text,
    name                    text NOT NULL,
    type                    varchar(50),
    rps                     numeric,
    latency                 numeric,
    error_rate              numeric,
    description             text,
    return_type             varchar(100),
    tech_capability_version_id bigint REFERENCES staging.tech_capability_versions (id),
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.operation_versions IS 'Версионированные данные операций';
COMMENT ON COLUMN staging.operation_versions.id IS 'Идентификатор версии операции';
COMMENT ON COLUMN staging.operation_versions.operation_id IS 'Операция в справочнике';
COMMENT ON COLUMN staging.operation_versions.interface_version_id IS 'Интерфейс версии';
COMMENT ON COLUMN staging.operation_versions.ext_uid IS 'Идентификатор операции в источнике';
COMMENT ON COLUMN staging.operation_versions.name IS 'Название операции';
COMMENT ON COLUMN staging.operation_versions.type IS 'Тип вызова GET/POST/PUT/PATCH/DELETE';
COMMENT ON COLUMN staging.operation_versions.rps IS 'Нагрузка, requests/sec';
COMMENT ON COLUMN staging.operation_versions.latency IS 'Время выполнения, ms';
COMMENT ON COLUMN staging.operation_versions.error_rate IS 'Процент ошибок, %';
COMMENT ON COLUMN staging.operation_versions.description IS 'Описание операции из product.operation (fdm-products)';
COMMENT ON COLUMN staging.operation_versions.return_type IS 'Тип возврата операции из product.operation (fdm-products)';
COMMENT ON COLUMN staging.operation_versions.tech_capability_version_id IS 'Техническая возможность, реализуемая операцией';
COMMENT ON COLUMN staging.operation_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.operation_versions.match_notice_id IS 'Match-замечание: причина привязки к сущности';
COMMENT ON COLUMN staging.operation_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_operation_versions_match_notice_id
    ON staging.operation_versions (match_notice_id);
CREATE INDEX IF NOT EXISTS idx_operation_versions_tech_capability_version_id
    ON staging.operation_versions (tech_capability_version_id);

-- ------------------------------------------------------------
-- 7. sequence_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.sequence_versions (
    id                      bigserial PRIMARY KEY,
    sequence_id             bigint REFERENCES staging.sequences (id),
    ext_uid                 text,
    name                    text,
    description             text,
    tech_capability_version_id bigint REFERENCES staging.tech_capability_versions (id),
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.sequence_versions IS 'Версионированные данные Sequence';
COMMENT ON COLUMN staging.sequence_versions.id IS 'Идентификатор версии Sequence';
COMMENT ON COLUMN staging.sequence_versions.sequence_id IS 'Sequence в справочнике';
COMMENT ON COLUMN staging.sequence_versions.ext_uid IS 'Внешний идентификатор Sequence в источнике';
COMMENT ON COLUMN staging.sequence_versions.name IS 'Название Sequence';
COMMENT ON COLUMN staging.sequence_versions.description IS 'Описание Sequence';
COMMENT ON COLUMN staging.sequence_versions.tech_capability_version_id IS 'Ссылка на ТС (дублирование для traces)';
COMMENT ON COLUMN staging.sequence_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.sequence_versions.match_notice_id IS 'Match-замечание: причина привязки к сущности';
COMMENT ON COLUMN staging.sequence_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_sequence_versions_tech_capability_version_id
    ON staging.sequence_versions (tech_capability_version_id);
CREATE INDEX IF NOT EXISTS idx_sequence_versions_match_notice_id
    ON staging.sequence_versions (match_notice_id);

-- ------------------------------------------------------------
-- 8. e2e_scenario_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.e2e_scenario_versions (
    id                      bigserial PRIMARY KEY,
    e2e_scenario_id         bigint REFERENCES staging.e2e_scenarios (id),
    bi_step_version_id      bigint REFERENCES staging.bi_step_versions (id),
    ext_uid                 text,
    name                    text,
    description             text,
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.e2e_scenario_versions IS 'Версионированные данные e2e сценариев';
COMMENT ON COLUMN staging.e2e_scenario_versions.id IS 'Идентификатор версии е2е сценария';
COMMENT ON COLUMN staging.e2e_scenario_versions.e2e_scenario_id IS 'Сценарий в справочнике';
COMMENT ON COLUMN staging.e2e_scenario_versions.bi_step_version_id IS '';
COMMENT ON COLUMN staging.e2e_scenario_versions.ext_uid IS 'Внешний идентификатор е2е сценария';
COMMENT ON COLUMN staging.e2e_scenario_versions.name IS 'Название е2е сценария';
COMMENT ON COLUMN staging.e2e_scenario_versions.description IS 'Описание е2е сценария';
COMMENT ON COLUMN staging.e2e_scenario_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.e2e_scenario_versions.match_notice_id IS 'Match-замечание: причина привязки к сущности';
COMMENT ON COLUMN staging.e2e_scenario_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_e2e_scenario_versions_match_notice_id
    ON staging.e2e_scenario_versions (match_notice_id);

-- ------------------------------------------------------------
-- 9. cj_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.cj_versions (
    id                      bigserial PRIMARY KEY,
    cj_id                   bigint REFERENCES staging.cjs (id),
    ext_uid                 text,
    name                    text,
    user_portrait           text,
    draft                   boolean,
    id_author               bigint,
    id_product_ext          bigint,
    unique_ident            text,
    dashboard_link          text,
    bpmn                    boolean,
    id_business_owner       bigint,
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.cj_versions IS 'Версионированные данные Customer Journey (CJ) из cx-backend';
COMMENT ON COLUMN staging.cj_versions.id IS 'Идентификатор версии CJ';
COMMENT ON COLUMN staging.cj_versions.cj_id IS 'CJ в справочнике';
COMMENT ON COLUMN staging.cj_versions.ext_uid IS 'Идентификатор CJ в источнике (cx-backend)';
COMMENT ON COLUMN staging.cj_versions.name IS 'Наименование клиентского пути из cx.cj';
COMMENT ON COLUMN staging.cj_versions.user_portrait IS 'Портрет пользователя из cx.cj';
COMMENT ON COLUMN staging.cj_versions.draft IS 'Признак черновика из cx.cj';
COMMENT ON COLUMN staging.cj_versions.id_author IS 'Автор из cx.cj';
COMMENT ON COLUMN staging.cj_versions.id_product_ext IS 'Продукт к которому относится CJ из cx.cj';
COMMENT ON COLUMN staging.cj_versions.unique_ident IS 'Уникальный идентификатор CJ из cx.cj';
COMMENT ON COLUMN staging.cj_versions.dashboard_link IS 'Ссылка на дашборд CJ из cx.cj';
COMMENT ON COLUMN staging.cj_versions.bpmn IS 'Флаг BPMN-основанного CJ из cx.cj';
COMMENT ON COLUMN staging.cj_versions.id_business_owner IS 'Бизнес-владелец CJ из cx.cj';
COMMENT ON COLUMN staging.cj_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.cj_versions.match_notice_id IS 'Match-замечание';
COMMENT ON COLUMN staging.cj_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_cj_versions_match_notice_id
    ON staging.cj_versions (match_notice_id);

-- ------------------------------------------------------------
-- 10. cj_step_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.cj_step_versions (
    id                      bigserial PRIMARY KEY,
    cj_step_id              bigint REFERENCES staging.cj_steps (id),
    cj_version_id           bigint REFERENCES staging.cj_versions (id),
    step_order              integer,
    ext_uid                 text,
    name                    text,
    description             text,
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.cj_step_versions IS 'Версионированные данные шагов Customer Journey (CJ Steps) из cx-backend';
COMMENT ON COLUMN staging.cj_step_versions.id IS 'Идентификатор версии шага CJ';
COMMENT ON COLUMN staging.cj_step_versions.cj_step_id IS 'Ссылка на шаг в справочнике';
COMMENT ON COLUMN staging.cj_step_versions.cj_version_id IS 'Ссылка на версию CJ';
COMMENT ON COLUMN staging.cj_step_versions.step_order IS 'Порядковый номер шага в CJ (cx.cj_steps.order)';
COMMENT ON COLUMN staging.cj_step_versions.ext_uid IS 'Внешний идентификатор шага в источнике (cx.cj_steps.id)';
COMMENT ON COLUMN staging.cj_step_versions.name IS 'Наименование шага из cx.cj_steps.name';
COMMENT ON COLUMN staging.cj_step_versions.description IS 'Описание шага из cx.cj_steps.description';
COMMENT ON COLUMN staging.cj_step_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.cj_step_versions.match_notice_id IS 'Match-замечание';
COMMENT ON COLUMN staging.cj_step_versions.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_cj_step_versions_match_notice_id
    ON staging.cj_step_versions (match_notice_id);
CREATE INDEX IF NOT EXISTS idx_cj_step_versions_cj_version_id
    ON staging.cj_step_versions (cj_version_id);

-- ============================================================
-- RELATION VERSION TABLES
-- ============================================================

-- ------------------------------------------------------------
-- 11. bi_step_relation_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.bi_step_relation_versions (
    id                      bigserial PRIMARY KEY,
    bi_step_version_id      bigint REFERENCES staging.bi_step_versions (id),
    operation_version_id    bigint REFERENCES staging.operation_versions (id),
    call_order              integer,
    stereotype              text,
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.bi_step_relation_versions IS 'Связь BI шага с операцией';
COMMENT ON COLUMN staging.bi_step_relation_versions.id IS 'Идентификатор версии связи';
COMMENT ON COLUMN staging.bi_step_relation_versions.bi_step_version_id IS 'BI шаг версии';
COMMENT ON COLUMN staging.bi_step_relation_versions.operation_version_id IS 'Операция версии';
COMMENT ON COLUMN staging.bi_step_relation_versions.call_order IS 'Порядок вызова операции в шаге BI';
COMMENT ON COLUMN staging.bi_step_relation_versions.stereotype IS 'Стереотип связи (вызов, обработка ошибки)';
COMMENT ON COLUMN staging.bi_step_relation_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.bi_step_relation_versions.match_notice_id IS 'Match-замечание';
COMMENT ON COLUMN staging.bi_step_relation_versions.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 12. operation_relation_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.operation_relation_versions (
    id                              bigserial PRIMARY KEY,
    operation_version_id            bigint REFERENCES staging.operation_versions (id),
    related_operation_version_id    bigint NOT NULL REFERENCES staging.operation_versions (id),
    call_order                      integer,
    stereotype                      text,
    raw_data_context_id             bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id                 bigint REFERENCES staging.artifact_notices (id),
    created_at                      timestamp DEFAULT now()
);
COMMENT ON TABLE staging.operation_relation_versions IS 'Связь между двумя операциями';
COMMENT ON COLUMN staging.operation_relation_versions.id IS 'Идентификатор версии связи';
COMMENT ON COLUMN staging.operation_relation_versions.operation_version_id IS 'Вызывающая операция';
COMMENT ON COLUMN staging.operation_relation_versions.related_operation_version_id IS 'Вызываемая операция';
COMMENT ON COLUMN staging.operation_relation_versions.call_order IS 'Порядок вызова в цепочке';
COMMENT ON COLUMN staging.operation_relation_versions.stereotype IS 'Стереотип связи (синхронный, асинхронный)';
COMMENT ON COLUMN staging.operation_relation_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.operation_relation_versions.match_notice_id IS 'Match-замечание';
COMMENT ON COLUMN staging.operation_relation_versions.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 13. sequence_relation_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.sequence_relation_versions (
    id                      bigserial PRIMARY KEY,
    sequence_version_id     bigint REFERENCES staging.sequence_versions (id),
    operation_version_id    bigint REFERENCES staging.operation_versions (id),
    call_order              integer,
    stereotype              text,
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    created_at              timestamp DEFAULT now()
);
COMMENT ON TABLE staging.sequence_relation_versions IS 'Связи внутри Sequence (caller → callee)';
COMMENT ON COLUMN staging.sequence_relation_versions.id IS 'Идентификатор версии связи Sequence';
COMMENT ON COLUMN staging.sequence_relation_versions.sequence_version_id IS 'Sequence версия';
COMMENT ON COLUMN staging.sequence_relation_versions.operation_version_id IS 'Вызываемая операция';
COMMENT ON COLUMN staging.sequence_relation_versions.call_order IS 'Порядок вызова в цепочке';
COMMENT ON COLUMN staging.sequence_relation_versions.stereotype IS 'Стереотип связи (синхронный, асинхронный)';
COMMENT ON COLUMN staging.sequence_relation_versions.raw_data_context_id IS 'Источник данных';
COMMENT ON COLUMN staging.sequence_relation_versions.match_notice_id IS 'Match-замечание';
COMMENT ON COLUMN staging.sequence_relation_versions.created_at IS 'Дата и время создания';
