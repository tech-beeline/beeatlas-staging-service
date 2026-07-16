-- ============================================================
-- V0004: Create stage tables
-- stage_tech_capabilities, stage_tech_capability_versions,
-- stage_sequences, stage_sequence_relations,
-- stage_bi_steps, stage_operations,
-- stage_bi_step_relations, stage_operation_relations
-- ============================================================

-- ------------------------------------------------------------
-- 1. stage_tech_capabilities
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.stage_tech_capabilities (
    id                      bigserial PRIMARY KEY,
    stage_id                bigint NOT NULL REFERENCES staging.stages (id),
    tech_capability_id      bigint REFERENCES staging.tech_capabilities (id),
    status                  text
);
COMMENT ON TABLE staging.stage_tech_capabilities IS 'Включение ТС в этапы';
COMMENT ON COLUMN staging.stage_tech_capabilities.id IS 'Идентификатор связи';
COMMENT ON COLUMN staging.stage_tech_capabilities.stage_id IS 'Этап';
COMMENT ON COLUMN staging.stage_tech_capabilities.tech_capability_id IS 'ТС';
COMMENT ON COLUMN staging.stage_tech_capabilities.status IS 'Статус ТС на этапе: active, inactive, deleted';

CREATE UNIQUE INDEX IF NOT EXISTS idx_stage_tech_capabilities_stage_tc
    ON staging.stage_tech_capabilities (stage_id, tech_capability_id);

-- ------------------------------------------------------------
-- 2. stage_tech_capability_versions
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.stage_tech_capability_versions (
    id                              bigserial PRIMARY KEY,
    stage_id                        bigint NOT NULL REFERENCES staging.stages (id),
    tech_capability_version_id      bigint REFERENCES staging.tech_capability_versions (id),
    status                          text,
    next_version_id                 bigint REFERENCES staging.tech_capability_versions (id)
);
COMMENT ON TABLE staging.stage_tech_capability_versions IS 'Включение версий ТС в этапы';
COMMENT ON COLUMN staging.stage_tech_capability_versions.id IS 'Идентификатор связи';
COMMENT ON COLUMN staging.stage_tech_capability_versions.stage_id IS 'Этап';
COMMENT ON COLUMN staging.stage_tech_capability_versions.tech_capability_version_id IS 'Версия ТС';
COMMENT ON COLUMN staging.stage_tech_capability_versions.status IS 'Статус версии ТС на этапе: active, inactive, deleted';
COMMENT ON COLUMN staging.stage_tech_capability_versions.next_version_id IS 'Следующая версия (цепочка)';

CREATE UNIQUE INDEX IF NOT EXISTS idx_stage_tech_capability_versions_stage_tcv
    ON staging.stage_tech_capability_versions (stage_id, tech_capability_version_id);

-- ------------------------------------------------------------
-- 3. stage_sequences
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.stage_sequences (
    id                      bigserial PRIMARY KEY,
    stage_id                bigint NOT NULL REFERENCES staging.stages (id),
    sequence_version_id     bigint REFERENCES staging.sequence_versions (id),
    status                  text
);
COMMENT ON TABLE staging.stage_sequences IS 'Включение Sequence в этапы';
COMMENT ON COLUMN staging.stage_sequences.id IS 'Идентификатор связи';
COMMENT ON COLUMN staging.stage_sequences.stage_id IS 'Этап';
COMMENT ON COLUMN staging.stage_sequences.sequence_version_id IS 'Версия Sequence';
COMMENT ON COLUMN staging.stage_sequences.status IS 'Статус Sequence на этапе: active, inactive, deleted';

CREATE UNIQUE INDEX IF NOT EXISTS idx_stage_sequences_stage_sv
    ON staging.stage_sequences (stage_id, sequence_version_id);

-- ------------------------------------------------------------
-- 4. stage_sequence_relations
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.stage_sequence_relations (
    id                              bigserial PRIMARY KEY,
    stage_id                        bigint REFERENCES staging.stages (id),
    sequence_relation_version_id    bigint REFERENCES staging.sequence_relation_versions (id),
    status                          text
);
COMMENT ON TABLE staging.stage_sequence_relations IS 'Включение связей Sequence в этапы';
COMMENT ON COLUMN staging.stage_sequence_relations.id IS 'Идентификатор связи';
COMMENT ON COLUMN staging.stage_sequence_relations.stage_id IS 'Этап';
COMMENT ON COLUMN staging.stage_sequence_relations.sequence_relation_version_id IS 'Связь Sequence версии';
COMMENT ON COLUMN staging.stage_sequence_relations.status IS 'Статус связи на этапе: active, inactive, deleted';

-- ------------------------------------------------------------
-- 5. stage_bi_steps
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.stage_bi_steps (
    id                      bigserial PRIMARY KEY,
    stage_id                bigint NOT NULL REFERENCES staging.stages (id),
    bi_step_version_id      bigint REFERENCES staging.bi_step_versions (id),
    status                  text
);
COMMENT ON TABLE staging.stage_bi_steps IS 'Включение BI шагов в этапы';
COMMENT ON COLUMN staging.stage_bi_steps.id IS 'Идентификатор связи';
COMMENT ON COLUMN staging.stage_bi_steps.stage_id IS 'Этап';
COMMENT ON COLUMN staging.stage_bi_steps.bi_step_version_id IS 'BI шаг версии';
COMMENT ON COLUMN staging.stage_bi_steps.status IS 'Статус BI шага на этапе: active, inactive, deleted';

CREATE UNIQUE INDEX IF NOT EXISTS idx_stage_bi_steps_stage_bsv
    ON staging.stage_bi_steps (stage_id, bi_step_version_id);

-- ------------------------------------------------------------
-- 6. stage_operations
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.stage_operations (
    id                      bigserial PRIMARY KEY,
    stage_id                bigint REFERENCES staging.stages (id),
    operation_version_id    bigint REFERENCES staging.operation_versions (id),
    status                  text
);
COMMENT ON TABLE staging.stage_operations IS 'Включение операций в этапы';
COMMENT ON COLUMN staging.stage_operations.id IS 'Идентификатор связи';
COMMENT ON COLUMN staging.stage_operations.stage_id IS 'Этап';
COMMENT ON COLUMN staging.stage_operations.operation_version_id IS 'Операция версии';
COMMENT ON COLUMN staging.stage_operations.status IS 'Статус операции на этапе: active, inactive, deleted';

-- ------------------------------------------------------------
-- 7. stage_bi_step_relations
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.stage_bi_step_relations (
    id                              bigserial PRIMARY KEY,
    stage_id                        bigint REFERENCES staging.stages (id),
    bi_step_relation_version_id     bigint REFERENCES staging.bi_step_relation_versions (id),
    status                          text
);
COMMENT ON TABLE staging.stage_bi_step_relations IS 'Включение связей BI в этапы';
COMMENT ON COLUMN staging.stage_bi_step_relations.id IS 'Идентификатор связи';
COMMENT ON COLUMN staging.stage_bi_step_relations.stage_id IS 'Этап';
COMMENT ON COLUMN staging.stage_bi_step_relations.bi_step_relation_version_id IS 'Связь BI версии';
COMMENT ON COLUMN staging.stage_bi_step_relations.status IS 'Статус связи на этапе: active, inactive, deleted';

-- ------------------------------------------------------------
-- 8. stage_operation_relations
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging.stage_operation_relations (
    id                                      bigserial PRIMARY KEY,
    stage_id                                bigint REFERENCES staging.stages (id),
    operation_relation_version_id           bigint REFERENCES staging.operation_relation_versions (id),
    status                                  text
);
COMMENT ON TABLE staging.stage_operation_relations IS 'Включение связей операций в этапы';
COMMENT ON COLUMN staging.stage_operation_relations.id IS 'Идентификатор связи';
COMMENT ON COLUMN staging.stage_operation_relations.stage_id IS 'Этап';
COMMENT ON COLUMN staging.stage_operation_relations.operation_relation_version_id IS 'Связь операции версии';
COMMENT ON COLUMN staging.stage_operation_relations.status IS 'Статус связи на этапе: active, inactive, deleted';
