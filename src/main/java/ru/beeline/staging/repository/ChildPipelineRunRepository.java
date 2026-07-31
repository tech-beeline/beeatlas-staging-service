/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.rundetails.ChildPipelineRun;

import java.util.List;

@Repository
public class ChildPipelineRunRepository {

    private static final String SELECT_CHILD_RUNS = """
            SELECT
                r.id,
                r.artifact_uid,
                r.artifact_type,
                r.status,
                s.name AS source_name,
                r.started_at,
                r.completed_at,
                r.failure_reason,
                r.failed_stage
            FROM staging.pipeline_runs r
                JOIN staging.configurations c ON c.id=r.configuration_id
                JOIN staging.source_systems s ON s.id=c.source_system_id
            WHERE r.parent_run_id=?
                AND (?::text IS NULL OR LOWER(r.status) = LOWER(?))
                AND (?::text IS NULL OR r.artifact_uid ILIKE '%' || ? || '%')
            ORDER BY r.started_at DESC, r.id DESC
            LIMIT ?
            OFFSET ?
            """;

    private final JdbcTemplate stagingJdbcTemplate;

    public ChildPipelineRunRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    public List<ChildPipelineRun> findChildRuns(Long parentId, String status, String artifactUid,
                                                 int limit, int offset) {
        return stagingJdbcTemplate.query(SELECT_CHILD_RUNS, (rs, rowNum) -> new ChildPipelineRun(
                rs.getLong("id"),
                rs.getString("artifact_uid"),
                rs.getString("artifact_type"),
                rs.getString("status"),
                rs.getString("source_name"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
                rs.getString("failure_reason"),
                rs.getString("failed_stage")
        ), parentId, status, status, artifactUid, artifactUid, limit, offset);
    }
}
