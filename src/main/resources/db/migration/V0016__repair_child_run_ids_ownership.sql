-- Разовая починка данных под дефект QA-4: child_run_ids скана и обратные ссылки
-- pipeline_runs.parent_run_id разъехались.
--
-- Причина (исправлена в PipelineRunService#finishScanWithChildren): скан, находя артефакт, у
-- которого уже есть незавершённый прогон, переиспользовал этот прогон и записывал его в свой
-- child_run_ids, не трогая parent_run_id. Один и тот же прогон таким образом оказывался в
-- снапшотах сотен сканов подряд, и childStats каждого из них рапортовал чужую работу как свою.
--
-- Инвариант после починки (и его поддерживает код): id лежит в child_run_ids скана S тогда и
-- только тогда, когда у этого прогона parent_run_id = S. Прогоны, у которых родитель — другой
-- скан, из снапшота вычищаются; сам прогон и его parent_run_id не меняются.

-- Индекс под массовый lookup по parent_run_id (нужен и починке, и запросам "дети скана").
CREATE INDEX IF NOT EXISTS idx_pipeline_runs_parent_run_id
    ON staging.pipeline_runs (parent_run_id)
    WHERE parent_run_id IS NOT NULL;

ANALYZE staging.pipeline_runs;

-- Сканы, в снапшоте которых есть хотя бы один чужой ребёнок. Отдельной таблицей, чтобы UPDATE
-- ниже не переписывал вообще все сканы (их сотни тысяч, а битых — единицы процентов).
CREATE TEMP TABLE tmp_scans_to_repair ON COMMIT DROP AS
SELECT DISTINCT s.id
FROM staging.pipeline_runs s
CROSS JOIN LATERAL jsonb_array_elements_text(s.child_run_ids) AS e(child_id)
LEFT JOIN staging.pipeline_runs c ON c.id = e.child_id::bigint
WHERE s.parent_run_id IS NULL
  AND s.child_run_ids IS NOT NULL
  AND jsonb_typeof(s.child_run_ids) = 'array'
  AND (c.id IS NULL OR c.parent_run_id IS DISTINCT FROM s.id);

CREATE UNIQUE INDEX ON tmp_scans_to_repair (id);
ANALYZE tmp_scans_to_repair;

-- COALESCE(..., '[]') — скан, у которого вся работа была чужой, получает пустой снапшот, а не
-- NULL: NULL в ScanRunRepository читается как "скан до V0014, снапшот не снимался".
UPDATE staging.pipeline_runs s
SET child_run_ids = COALESCE((
        SELECT jsonb_agg(e.child_id::bigint ORDER BY e.child_id::bigint)
        FROM jsonb_array_elements_text(s.child_run_ids) AS e(child_id)
        JOIN staging.pipeline_runs c ON c.id = e.child_id::bigint
        WHERE c.parent_run_id = s.id
    ), '[]'::jsonb)
FROM tmp_scans_to_repair r
WHERE r.id = s.id;
