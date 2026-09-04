ALTER TABLE staging.pipeline_runs ADD COLUMN child_run_ids jsonb;

COMMENT ON COLUMN staging.pipeline_runs.child_run_ids IS
    'Снапшот id дочерних pipeline_runs, зафиксированный один раз в момент fan-out скана (только для '
    'сканов, parent_run_id IS NULL). В отличие от childStats, вычисляемого через '
    'source_artifacts.last_seen_scan_run_id, этот список не "утекает" к более новому скану той же '
    'конфигурации — id дочерних ранов не меняются, даже если сам артефакт позже переоткрывается '
    'новым сканом.';
