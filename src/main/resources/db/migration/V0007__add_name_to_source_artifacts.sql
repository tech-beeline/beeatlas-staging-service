-- ============================================================
-- V0007: Add readable name column to staging.source_artifacts
-- ============================================================
-- BLG-002: поле name для читаемой идентификации артефакта из преадаптера.
-- Источник значения — FoundArtifact.metadata (ключ name для Sparx E2E —
-- scenario.getName(); ключ productName для Structurizr sequence —
-- product.getName()).
-- Требования: REQ-staging-003 FR-003-16..19
--   - FR-003-17: поле опционально (BR-13), отсутствие не блокирует пайплайн;
--   - FR-003-18: fallback для отображения — ext_uid + тип артефакта;
--   - FR-003-19: обратная совместимость — миграция не теряет данные,
--     не меняет семантику существующих полей, без DEFAULT.
-- Колонка добавляется как text nullable без DEFAULT: существующие строки
-- получают NULL в name, что соответствует семантике «имя неизвестно».
-- Идемпотентность обеспечивается ADD COLUMN IF NOT EXISTS.
-- ============================================================

ALTER TABLE staging.source_artifacts
    ADD COLUMN IF NOT EXISTS name text;

COMMENT ON COLUMN staging.source_artifacts.name IS
    'Читаемое имя артефакта из преадаптера (fallback: ext_uid + тип)';
