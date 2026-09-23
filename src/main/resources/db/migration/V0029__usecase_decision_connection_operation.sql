-- ============================================================
-- V0029: Решения фазы 2 указывают архитектурную операцию (SFDM-4149, ADR-029)
-- Решение адресует шаг, записанный saver-ом, и несёт операцию из fdm-products;
-- create_new заменён на planned (шаг осознанно остаётся без архитектурной операции).
-- ============================================================

ALTER TABLE staging.import_decisions
    ADD COLUMN IF NOT EXISTS connection_operation_json jsonb;

COMMENT ON COLUMN staging.import_decisions.connection_operation_json IS
    'Архитектурная операция из fdm-products: {id, operationType, operationName, interfaceCode, containerCode, productAlias}';

COMMENT ON COLUMN staging.import_decisions.target_json IS
    'Шаг, к которому относится решение: {stepVersionId, type, name, productAlias, interfaceCode}';

ALTER TABLE staging.import_decisions DROP CONSTRAINT IF EXISTS chk_import_decisions_type;

ALTER TABLE staging.import_decisions
    ADD CONSTRAINT chk_import_decisions_type
        CHECK (decision_type IN ('map_existing', 'create_new', 'planned'));
