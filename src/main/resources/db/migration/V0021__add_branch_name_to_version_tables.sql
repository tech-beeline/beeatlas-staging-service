ALTER TABLE staging.bi_step_versions          ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.tech_capability_versions  ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.product_versions          ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.container_versions        ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.interface_versions        ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.operation_versions        ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.sequence_versions         ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.e2e_scenario_versions     ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.cj_versions               ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.cj_step_versions          ADD COLUMN IF NOT EXISTS branch_name varchar(64);

ALTER TABLE staging.bi_step_relation_versions   ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.operation_relation_versions ADD COLUMN IF NOT EXISTS branch_name varchar(64);
ALTER TABLE staging.sequence_relation_versions  ADD COLUMN IF NOT EXISTS branch_name varchar(64);

COMMENT ON COLUMN staging.bi_step_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.tech_capability_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.product_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.container_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.interface_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.operation_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.sequence_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.e2e_scenario_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.cj_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.cj_step_versions.branch_name IS 'Ветка версии: свободная метка (ADR-013), NULL для легаси';
COMMENT ON COLUMN staging.bi_step_relation_versions.branch_name IS 'Ветка связи: та же, что у версий по краям связи';
COMMENT ON COLUMN staging.operation_relation_versions.branch_name IS 'Ветка связи: та же, что у версий по краям связи';
COMMENT ON COLUMN staging.sequence_relation_versions.branch_name IS 'Ветка связи: та же, что у версий по краям связи';

UPDATE staging.bi_step_versions          SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.tech_capability_versions  SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.product_versions          SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.container_versions        SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.interface_versions        SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.operation_versions        SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.sequence_versions         SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.e2e_scenario_versions     SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.cj_versions               SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.cj_step_versions          SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.bi_step_relation_versions   SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.operation_relation_versions SET branch_name = 'main' WHERE branch_name IS NULL;
UPDATE staging.sequence_relation_versions  SET branch_name = 'main' WHERE branch_name IS NULL;

CREATE INDEX IF NOT EXISTS idx_bi_step_versions_branch_current
    ON staging.bi_step_versions (bi_step_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_tech_capability_versions_branch_current
    ON staging.tech_capability_versions (tech_capability_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_product_versions_branch_current
    ON staging.product_versions (product_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_container_versions_branch_current
    ON staging.container_versions (container_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_interface_versions_branch_current
    ON staging.interface_versions (interface_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_operation_versions_branch_current
    ON staging.operation_versions (operation_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_sequence_versions_branch_current
    ON staging.sequence_versions (sequence_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_e2e_scenario_versions_branch_current
    ON staging.e2e_scenario_versions (e2e_scenario_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_cj_versions_branch_current
    ON staging.cj_versions (cj_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_cj_step_versions_branch_current
    ON staging.cj_step_versions (cj_step_id, branch_name, created_at);
