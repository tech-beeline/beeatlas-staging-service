-- ============================================================
-- V0028: Двухфазная запись UseCase (SFDM-4149, ADR-029)
-- Шаги UseCase ссылаются на operation_versions напрямую (caller/callee);
-- контекст паузы собирается из канонической модели — draft_json упразднён.
-- ============================================================

ALTER TABLE staging.usecase_versions
    ADD COLUMN IF NOT EXISTS run_id bigint REFERENCES staging.pipeline_runs (id);

COMMENT ON COLUMN staging.usecase_versions.run_id IS
    'Запуск, записавший версию (saver, фаза 1); по нему собирается контекст паузы этапа manual';

CREATE INDEX IF NOT EXISTS usecase_versions_run_idx ON staging.usecase_versions (run_id);

ALTER TABLE staging.usecase_step_versions
    ADD COLUMN IF NOT EXISTS operation_version_id bigint REFERENCES staging.operation_versions (id),
    ADD COLUMN IF NOT EXISTS callee_operation_version_id bigint REFERENCES staging.operation_versions (id);

COMMENT ON COLUMN staging.usecase_step_versions.operation_version_id IS
    'Вызывающая операция (caller) — версия операции; пусто — несмаппированная сторона (+ notice)';
COMMENT ON COLUMN staging.usecase_step_versions.callee_operation_version_id IS
    'Вызываемая операция (callee) — версия операции; пусто — несмаппированная сторона (+ notice)';

CREATE INDEX IF NOT EXISTS usecase_step_versions_op_idx
    ON staging.usecase_step_versions (operation_version_id);
CREATE INDEX IF NOT EXISTS usecase_step_versions_callee_op_idx
    ON staging.usecase_step_versions (callee_operation_version_id);

ALTER TABLE staging.pipeline_runs DROP COLUMN IF EXISTS draft_json;
