CREATE INDEX IF NOT EXISTS idx_bi_step_relation_versions_branch_current
    ON staging.bi_step_relation_versions (bi_step_version_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_operation_relation_versions_branch_current
    ON staging.operation_relation_versions (operation_version_id, branch_name, created_at);
CREATE INDEX IF NOT EXISTS idx_sequence_relation_versions_branch_current
    ON staging.sequence_relation_versions (sequence_version_id, branch_name, created_at);
