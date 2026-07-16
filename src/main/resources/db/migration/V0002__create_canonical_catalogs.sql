-- ============================================================
-- V0002: Create canonical catalog tables (справочники)
-- bi_steps, e2e_scenarios, interfaces, operations,
-- tech_capabilities, sequences, products, containers, cjs, cj_steps
-- ============================================================

-- ------------------------------------------------------------
-- 1. bi_steps
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.bi_steps (
    id          bigserial PRIMARY KEY,
    uid         text UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.bi_steps IS 'Канонический справочник BI шагов. Только id + uid + created_at согласно правилу 2';
COMMENT ON COLUMN staging.bi_steps.id IS 'Локальный идентификатор BI шага';
COMMENT ON COLUMN staging.bi_steps.uid IS 'UID BI шага';
COMMENT ON COLUMN staging.bi_steps.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 2. e2e_scenarios
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.e2e_scenarios (
    id          bigserial PRIMARY KEY,
    uid         text UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.e2e_scenarios IS 'Канонический справочник е2е сценариев. Только id + uid + created_at согласно правилу 2';
COMMENT ON COLUMN staging.e2e_scenarios.id IS 'Идентификатор е2е сценария';
COMMENT ON COLUMN staging.e2e_scenarios.uid IS 'Уникальный идентификатор, общий для всех версий';
COMMENT ON COLUMN staging.e2e_scenarios.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 3. interfaces
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.interfaces (
    id          bigserial PRIMARY KEY,
    uid         text UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.interfaces IS 'Канонический справочник интерфейсов. Только id + uid + created_at согласно правилу 2';
COMMENT ON COLUMN staging.interfaces.id IS 'Идентификатор интерфейса';
COMMENT ON COLUMN staging.interfaces.uid IS 'Уникальный идентификатор, общий для всех версий';
COMMENT ON COLUMN staging.interfaces.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 4. operations
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.operations (
    id          bigserial PRIMARY KEY,
    uid         text UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.operations IS 'Канонический справочник операций. Только id + uid + created_at согласно правилу 2';
COMMENT ON COLUMN staging.operations.id IS 'Идентификатор операции';
COMMENT ON COLUMN staging.operations.uid IS 'Внешний уникальный идентификатор';
COMMENT ON COLUMN staging.operations.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 5. tech_capabilities
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.tech_capabilities (
    id          bigserial PRIMARY KEY,
    uid         text NOT NULL UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.tech_capabilities IS 'Канонический справочник ТС (Technology Capabilities). Только id + uid + created_at согласно правилу 2';
COMMENT ON COLUMN staging.tech_capabilities.id IS 'Идентификатор ТС';
COMMENT ON COLUMN staging.tech_capabilities.uid IS 'Уникальный идентификатор ТС';
COMMENT ON COLUMN staging.tech_capabilities.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 6. sequences
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.sequences (
    id          bigserial PRIMARY KEY,
    uid         text NOT NULL UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.sequences IS 'Sequence — динамический вид одного продукта, привязанный к TC. Только id + uid + created_at согласно правилу 2';
COMMENT ON COLUMN staging.sequences.id IS 'Идентификатор Sequence';
COMMENT ON COLUMN staging.sequences.uid IS 'Уникальный идентификатор Sequence';
COMMENT ON COLUMN staging.sequences.created_at IS 'Дата и время создания';

CREATE INDEX IF NOT EXISTS idx_sequences_uid ON staging.sequences (uid);

-- ------------------------------------------------------------
-- 7. products
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.products (
    id          bigserial PRIMARY KEY,
    uid         text NOT NULL UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.products IS 'Канонический справочник продуктов BeeAtlas (identity). Только id + uid + created_at согласно правилу 2';
COMMENT ON COLUMN staging.products.id IS 'Идентификатор продукта';
COMMENT ON COLUMN staging.products.uid IS 'Уникальный идентификатор продукта';
COMMENT ON COLUMN staging.products.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 8. containers
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.containers (
    id          bigserial PRIMARY KEY,
    uid         text NOT NULL UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.containers IS 'Канонический справочник контейнеров (identity). Только id + uid + created_at согласно правилу 2';
COMMENT ON COLUMN staging.containers.id IS 'Идентификатор контейнера';
COMMENT ON COLUMN staging.containers.uid IS 'Уникальный идентификатор контейнера';
COMMENT ON COLUMN staging.containers.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 9. cjs
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.cjs (
    id          bigserial PRIMARY KEY,
    uid         text UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.cjs IS 'Канонический справочник Customer Journey (CJ) из cx-backend';
COMMENT ON COLUMN staging.cjs.id IS 'Идентификатор CJ';
COMMENT ON COLUMN staging.cjs.uid IS 'Уникальный идентификатор, общий для всех версий';
COMMENT ON COLUMN staging.cjs.created_at IS 'Дата и время создания';

-- ------------------------------------------------------------
-- 10. cj_steps
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.cj_steps (
    id          bigserial PRIMARY KEY,
    uid         text UNIQUE,
    created_at  timestamp DEFAULT now()
);
COMMENT ON TABLE staging.cj_steps IS 'Канонический справочник шагов Customer Journey (CJ Steps) из cx-backend';
COMMENT ON COLUMN staging.cj_steps.id IS 'Идентификатор шага CJ';
COMMENT ON COLUMN staging.cj_steps.uid IS 'Уникальный идентификатор шага CJ, общий для всех версий';
COMMENT ON COLUMN staging.cj_steps.created_at IS 'Дата и время создания';
