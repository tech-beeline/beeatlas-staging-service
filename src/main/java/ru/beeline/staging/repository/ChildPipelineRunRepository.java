/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.rundetails.ChildPipelineRun;
import ru.beeline.staging.dto.rundetails.ChildPipelineRunPage;

import java.util.List;

@Repository
public class ChildPipelineRunRepository {

    private static final String WHERE_CLAUSE = """
            WHERE r.parent_run_id=?
                AND (?::text IS NULL OR LOWER(r.status) = LOWER(?))
                AND (?::text IS NULL OR r.artifact_uid ILIKE '%' || ? || '%')
            """;

    private static final String COUNT_CHILD_RUNS = """
            SELECT count(*)
            FROM staging.pipeline_runs r
            """ + WHERE_CLAUSE;

    private static final String SELECT_CHILD_RUNS = """
            SELECT
                r.id,
                r.artifact_uid,
                sa.name AS artifact_name,
                r.artifact_type,
                r.status,
                r.raw_data_ref_id,
                s.name AS source_name,
                r.started_at,
                adapter_log.started_at AS processing_started_at,
                r.completed_at,
                r.failure_reason,
                r.failed_stage
            FROM staging.pipeline_runs r
                JOIN staging.configurations c ON c.id=r.configuration_id
                JOIN staging.source_systems s ON s.id=c.source_system_id
                JOIN staging.data_types t ON t.code=r.artifact_type
                JOIN staging.source_artifact_types sat ON sat.data_type_id=t.id
                LEFT JOIN staging.source_artifacts sa ON sa.ext_uid = r.artifact_uid
                    AND sa.source_artifact_type_id=sat.id
                -- started_at on pipeline_runs is when the row was created (queued, possibly by an
                -- earlier scan that this run got reused from) — processing_started_at is when the
                -- adapter stage actually began, which is what "how long did this artifact take"
                -- should be measured from, not queue wait.
                LEFT JOIN LATERAL (
                    SELECT psl.started_at
                    FROM staging.pipeline_stage_logs psl
                    WHERE psl.run_id = r.id AND psl.stage_name = 'adapter'
                    ORDER BY psl.started_at ASC
                    LIMIT 1
                ) adapter_log ON true
            """ + WHERE_CLAUSE + """
            ORDER BY r.started_at DESC, r.id DESC
            LIMIT ?
            OFFSET ?
            """;

    private final JdbcTemplate stagingJdbcTemplate;

    public ChildPipelineRunRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    public ChildPipelineRunPage findChildRuns(Long parentId, String status, String artifactUid,
                                               int limit, int offset) {
        Long totalCount = stagingJdbcTemplate.queryForObject(COUNT_CHILD_RUNS, Long.class,
                parentId, status, status, artifactUid, artifactUid);

        List<ChildPipelineRun> results = stagingJdbcTemplate.query(SELECT_CHILD_RUNS, (rs, rowNum) -> new ChildPipelineRun(
                rs.getLong("id"),
                rs.getString("artifact_uid"),
                rs.getString("artifact_name"),
                rs.getString("artifact_type"),
                rs.getString("status"),
                (Long) rs.getObject("raw_data_ref_id"),
                rs.getString("source_name"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("processing_started_at") != null ? rs.getTimestamp("processing_started_at").toLocalDateTime() : null,
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
                rs.getString("failure_reason"),
                rs.getString("failed_stage")
        ), parentId, status, status, artifactUid, artifactUid, limit, offset);

        return new ChildPipelineRunPage(totalCount != null ? totalCount : 0, results);
    }
}
