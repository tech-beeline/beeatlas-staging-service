-- ===================================================================
-- V0009: raw_data_context linking on versions/notices
-- ===================================================================
-- Добавляет raw_data_context_id (структурированная позиция в сыром JSON, json_path)
-- на version/relation-таблицы e2e-модели и на artifact_notices — новый код пишет это
-- поле вместо свободнотекстового context. Старая колонка context TEXT не удаляется —
-- её использует код Tc/Sequence.
-- ===================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging' AND table_name = 'bi_step_versions'
          AND column_name = 'raw_data_context_id'
    ) THEN
        ALTER TABLE staging.bi_step_versions
            ADD COLUMN raw_data_context_id uuid REFERENCES staging.raw_data_context(id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging' AND table_name = 'interface_versions'
          AND column_name = 'raw_data_context_id'
    ) THEN
        ALTER TABLE staging.interface_versions
            ADD COLUMN raw_data_context_id uuid REFERENCES staging.raw_data_context(id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging' AND table_name = 'operation_versions'
          AND column_name = 'raw_data_context_id'
    ) THEN
        ALTER TABLE staging.operation_versions
            ADD COLUMN raw_data_context_id uuid REFERENCES staging.raw_data_context(id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging' AND table_name = 'bi_step_relation_versions'
          AND column_name = 'raw_data_context_id'
    ) THEN
        ALTER TABLE staging.bi_step_relation_versions
            ADD COLUMN raw_data_context_id uuid REFERENCES staging.raw_data_context(id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging' AND table_name = 'operation_relation_versions'
          AND column_name = 'raw_data_context_id'
    ) THEN
        ALTER TABLE staging.operation_relation_versions
            ADD COLUMN raw_data_context_id uuid REFERENCES staging.raw_data_context(id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging' AND table_name = 'artifact_notices'
          AND column_name = 'raw_data_context_id'
    ) THEN
        ALTER TABLE staging.artifact_notices
            ADD COLUMN raw_data_context_id uuid REFERENCES staging.raw_data_context(id);
    END IF;
END $$;

COMMENT ON COLUMN staging.bi_step_versions.raw_data_context_id        IS 'Структурированная позиция в сыром JSON (json_path) — замена свободнотекстового context для нового кода';
COMMENT ON COLUMN staging.interface_versions.raw_data_context_id      IS 'Структурированная позиция в сыром JSON (json_path) — замена свободнотекстового context для нового кода';
COMMENT ON COLUMN staging.operation_versions.raw_data_context_id      IS 'Структурированная позиция в сыром JSON (json_path) — замена свободнотекстового context для нового кода';
COMMENT ON COLUMN staging.bi_step_relation_versions.raw_data_context_id IS 'Структурированная позиция в сыром JSON (json_path) — замена свободнотекстового context для нового кода';
COMMENT ON COLUMN staging.operation_relation_versions.raw_data_context_id IS 'Структурированная позиция в сыром JSON (json_path) — замена свободнотекстового context для нового кода';
COMMENT ON COLUMN staging.artifact_notices.raw_data_context_id        IS 'Структурированная позиция в сыром JSON (json_path) — замена свободнотекстового context для нового кода';
