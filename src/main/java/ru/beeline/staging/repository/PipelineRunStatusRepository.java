/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.pipelinerun.PipelineRunStatusSnapshot;

import java.util.List;
import java.util.Optional;

@Repository
public class PipelineRunStatusRepository {

    private static final String SELECT_STATUS = "SELECT status FROM staging.pipeline_runs WHERE id = ?";

    private static final String SELECT_SNAPSHOT = """
            SELECT
                r.id AS run_id,
                r.artifact_type,
                r.artifact_uid,
                r.status,
                (SELECT l.stage_name
                   FROM staging.pipeline_stage_logs l
                  WHERE l.run_id = r.id
                  ORDER BY l.started_at DESC, l.id DESC
                  LIMIT 1) AS stage,
                (SELECT count(*)
                   FROM staging.artifact_notices an
                   JOIN staging.raw_data_contexts rdc ON rdc.id = an.raw_data_context_id
                  WHERE rdc.raw_data_ref_id = r.raw_data_ref_id) AS notices_count
            FROM staging.pipeline_runs r
            WHERE r.id = ?
            """;

    private final JdbcTemplate stagingJdbcTemplate;

    public PipelineRunStatusRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    public Optional<String> findStatus(Long runId) {
        List<String> rows = stagingJdbcTemplate.queryForList(SELECT_STATUS, String.class, runId);
        return rows.stream().findFirst();
    }

    public Optional<PipelineRunStatusSnapshot> findSnapshot(Long runId) {
        List<PipelineRunStatusSnapshot> rows = stagingJdbcTemplate.query(SELECT_SNAPSHOT, (rs, rowNum) ->
                new PipelineRunStatusSnapshot(
                        rs.getLong("run_id"),
                        rs.getString("artifact_type"),
                        rs.getString("artifact_uid"),
                        rs.getString("status"),
                        rs.getString("stage"),
                        rs.getInt("notices_count")), runId);
        return rows.stream().findFirst();
    }
}
