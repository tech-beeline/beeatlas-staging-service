-- ============================================================
-- V0024: UseCase import (SFDM-4070, REQ-staging-009/010/012, ADR-028)
-- Canonical UseCase model, planned requirements and HITL decisions.
-- ============================================================

CREATE TABLE IF NOT EXISTS staging.usecases (
    id              bigserial PRIMARY KEY,
    uid             text NOT NULL UNIQUE,
    project_code    text,
    created_at      timestamp NOT NULL DEFAULT now()
);
COMMENT ON TABLE staging.usecases IS 'Справочник UseCase (identity)';
COMMENT ON COLUMN staging.usecases.uid IS 'Код UseCase (например, UC-001); дедупликация';
COMMENT ON COLUMN staging.usecases.project_code IS 'Код проекта, в котором создан UseCase';

CREATE TABLE IF NOT EXISTS staging.usecase_versions (
    id                  bigserial PRIMARY KEY,
    usecase_id          bigint REFERENCES staging.usecases (id),
    bi_step_version_id  bigint REFERENCES staging.bi_step_versions (id),
    ext_uid             text,
    name                text,
    project_code        text,
    branch_name         varchar(64),
    json_data           jsonb CONSTRAINT chk_usecase_versions_json_data
                            CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object'),
    raw_data_context_id bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id     bigint REFERENCES staging.artifact_notices (id),
    created_at          timestamp NOT NULL DEFAULT now()
);
COMMENT ON TABLE staging.usecase_versions IS 'Версии UseCase (append-only)';
COMMENT ON COLUMN staging.usecase_versions.bi_step_version_id IS 'Привязка UseCase к CJ через Bi step';
COMMENT ON COLUMN staging.usecase_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), из pipeline_runs.branch';
COMMENT ON COLUMN staging.usecase_versions.json_data IS 'Неосновные атрибуты: code, description, plant_uml, bi_step_code';

CREATE INDEX IF NOT EXISTS usecase_versions_usecase_idx ON staging.usecase_versions (usecase_id);
CREATE INDEX IF NOT EXISTS usecase_versions_bi_step_idx ON staging.usecase_versions (bi_step_version_id);
CREATE INDEX IF NOT EXISTS usecase_versions_match_notice_idx ON staging.usecase_versions (match_notice_id);
CREATE INDEX IF NOT EXISTS usecase_versions_project_idx ON staging.usecase_versions (project_code);
CREATE INDEX IF NOT EXISTS usecase_versions_branch_current_idx
    ON staging.usecase_versions (usecase_id, branch_name, created_at);

CREATE TABLE IF NOT EXISTS staging.required_operations (
    id          bigserial PRIMARY KEY,
    uid         text NOT NULL UNIQUE,
    usecase_id  bigint REFERENCES staging.usecases (id),
    name        text NOT NULL,
    type        varchar(20) NOT NULL CONSTRAINT chk_required_operations_type CHECK (type IN ('operation', 'interface')),
    created_at  timestamp NOT NULL DEFAULT now()
);
COMMENT ON TABLE staging.required_operations IS 'Справочник плановых требований (identity)';
COMMENT ON COLUMN staging.required_operations.uid IS '{usecase_id}:{type}:{name}; дедупликация';

CREATE INDEX IF NOT EXISTS required_operations_usecase_idx ON staging.required_operations (usecase_id);
CREATE INDEX IF NOT EXISTS required_operations_type_idx ON staging.required_operations (type);

CREATE TABLE IF NOT EXISTS staging.required_operation_versions (
    id                      bigserial PRIMARY KEY,
    required_operation_id   bigint NOT NULL REFERENCES staging.required_operations (id),
    ext_uid                 text,
    run_id                  bigint REFERENCES staging.pipeline_runs (id),
    usecase_version_id      bigint REFERENCES staging.usecase_versions (id),
    product_uid             text NOT NULL,
    status                  varchar(20) NOT NULL DEFAULT 'required'
                                CONSTRAINT chk_required_operation_versions_status CHECK (status IN ('required', 'matched')),
    branch_name             varchar(64),
    operation_version_id    bigint REFERENCES staging.operation_versions (id),
    match_notice_id         bigint REFERENCES staging.artifact_notices (id),
    raw_data_context_id     bigint REFERENCES staging.raw_data_contexts (id),
    json_data               jsonb CONSTRAINT chk_required_operation_versions_json_data
                                CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object'),
    created_at              timestamp NOT NULL DEFAULT now(),
    updated_at              timestamp NOT NULL DEFAULT now()
);
COMMENT ON TABLE staging.required_operation_versions IS 'Версии/состояния плановых требований; статус сверки обновляется in-place';
COMMENT ON COLUMN staging.required_operation_versions.status IS 'required — не найдено в ландшафте; matched — найдено (REQ-012)';
COMMENT ON COLUMN staging.required_operation_versions.operation_version_id IS 'Версия операции при совпадении';
COMMENT ON COLUMN staging.required_operation_versions.json_data IS 'Коды operation/interface/container и требования create_new';

CREATE INDEX IF NOT EXISTS required_operation_versions_req_op_idx ON staging.required_operation_versions (required_operation_id);
CREATE INDEX IF NOT EXISTS required_operation_versions_run_idx ON staging.required_operation_versions (run_id);
CREATE INDEX IF NOT EXISTS required_operation_versions_usecase_idx ON staging.required_operation_versions (usecase_version_id);
CREATE INDEX IF NOT EXISTS required_operation_versions_product_idx ON staging.required_operation_versions (product_uid);
CREATE INDEX IF NOT EXISTS required_operation_versions_status_idx ON staging.required_operation_versions (status);
CREATE INDEX IF NOT EXISTS required_operation_versions_op_version_idx ON staging.required_operation_versions (operation_version_id);
CREATE INDEX IF NOT EXISTS required_operation_versions_branch_current_idx
    ON staging.required_operation_versions (required_operation_id, branch_name, created_at);

CREATE TABLE IF NOT EXISTS staging.usecase_step_versions (
    id                                      bigserial PRIMARY KEY,
    usecase_version_id                      bigint REFERENCES staging.usecase_versions (id),
    required_operation_version_id           bigint REFERENCES staging.required_operation_versions (id),
    callee_required_operation_version_id    bigint REFERENCES staging.required_operation_versions (id),
    ext_uid                                 text,
    name                                    text,
    seq                                     integer,
    scenario_type                           varchar(20),
    call_status                             varchar(30),
    step_type                               varchar(20),
    branch_name                             varchar(64),
    json_data                               jsonb CONSTRAINT chk_usecase_step_versions_json_data
                                                CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object'),
    raw_data_context_id                     bigint REFERENCES staging.raw_data_contexts (id),
    match_notice_id                         bigint REFERENCES staging.artifact_notices (id),
    created_at                              timestamp NOT NULL DEFAULT now()
);
COMMENT ON TABLE staging.usecase_step_versions IS 'Версии шагов UseCase (без справочника)';
COMMENT ON COLUMN staging.usecase_step_versions.required_operation_version_id IS 'Вызывающая операция (caller)';
COMMENT ON COLUMN staging.usecase_step_versions.callee_required_operation_version_id IS 'Вызываемая операция (callee)';
COMMENT ON COLUMN staging.usecase_step_versions.ext_uid IS 'partId части UseCase (P-01)';
COMMENT ON COLUMN staging.usecase_step_versions.call_status IS 'confirmed / architect_specified / planned';

CREATE INDEX IF NOT EXISTS usecase_step_versions_usecase_idx ON staging.usecase_step_versions (usecase_version_id);
CREATE INDEX IF NOT EXISTS usecase_step_versions_usecase_seq_idx ON staging.usecase_step_versions (usecase_version_id, seq);
CREATE INDEX IF NOT EXISTS usecase_step_versions_req_op_idx ON staging.usecase_step_versions (required_operation_version_id);
CREATE INDEX IF NOT EXISTS usecase_step_versions_callee_idx ON staging.usecase_step_versions (callee_required_operation_version_id);
CREATE INDEX IF NOT EXISTS usecase_step_versions_match_notice_idx ON staging.usecase_step_versions (match_notice_id);

CREATE TABLE IF NOT EXISTS staging.import_decisions (
    id                  bigserial PRIMARY KEY,
    run_id              bigint NOT NULL REFERENCES staging.pipeline_runs (id),
    part_id             text,
    decision_type       varchar(20) NOT NULL
                            CONSTRAINT chk_import_decisions_type CHECK (decision_type IN ('map_existing', 'create_new')),
    target_json         jsonb,
    new_request_json    jsonb,
    created_at          timestamp NOT NULL DEFAULT now(),
    updated_at          timestamp NOT NULL DEFAULT now(),
    CONSTRAINT uq_import_decisions_run_part UNIQUE (run_id, part_id)
);
COMMENT ON TABLE staging.import_decisions IS 'Решения пользователя по пунктам паузы HITL; одно актуальное решение на (run_id, part_id)';
COMMENT ON COLUMN staging.import_decisions.target_json IS 'map_existing: {containerCode, interfaceCode}';
COMMENT ON COLUMN staging.import_decisions.new_request_json IS 'create_new: {productCode, containerName, interfaceName, protocol, note}';

CREATE INDEX IF NOT EXISTS import_decisions_part_idx ON staging.import_decisions (part_id);

INSERT INTO staging.configurations (code, artifact_type, data_type_id, source_system_id, schedule_interval_seconds, is_active)
VALUES (
    'usecase-manual',
    'usecase',
    (SELECT id FROM staging.data_types WHERE code = 'usecase'),
    (SELECT id FROM staging.source_systems WHERE code = 'beeatlas-ui'),
    NULL,
    true
)
ON CONFLICT (code) DO NOTHING;
