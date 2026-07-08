-- Добавляет interface_versions.source (manual/structurizr) — откуда взят сам интерфейс в Sparx EA
-- (interfaces[].source в сыром экспорте): вручную заведённый ProvidedInterface, либо выведенный
-- через C4-модель Structurizr (Realisation-связи software system → C4_Container → Interface).

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'staging' AND table_name = 'interface_versions'
          AND column_name = 'source'
    ) THEN
        ALTER TABLE staging.interface_versions
            ADD COLUMN source VARCHAR(20)
                CONSTRAINT interface_versions_source_check CHECK (source IS NULL OR source IN ('manual', 'structurizr'));
    END IF;
END $$;

COMMENT ON COLUMN staging.interface_versions.source IS
    'Источник интерфейса в Sparx EA: manual (вручную заведён) или structurizr (выведен из C4-модели)';
