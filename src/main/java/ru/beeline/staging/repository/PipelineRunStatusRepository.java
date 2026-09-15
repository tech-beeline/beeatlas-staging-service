/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.pipelinerun.PipelineRunStatusSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Repository
public class PipelineRunStatusRepository {

    private static final Set<String> STATUSES_WITH_RESULT = Set.of("awaiting_review", "reviewing", "completed");

    private static final String SELECT_STATUS = "SELECT status FROM staging.pipeline_runs WHERE id = ?";

    private static final String SELECT_SNAPSHOT = """
            SELECT
                r.id AS run_id,
                r.artifact_type,
                r.artifact_uid,
                r.status,
                r.draft_json,
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
    private final ObjectMapper objectMapper;

    public PipelineRunStatusRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate,
                                       ObjectMapper objectMapper) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<String> findStatus(Long runId) {
        List<String> rows = stagingJdbcTemplate.queryForList(SELECT_STATUS, String.class, runId);
        return rows.stream().findFirst();
    }

    public Optional<PipelineRunStatusSnapshot> findSnapshot(Long runId) {
        List<PipelineRunStatusSnapshot> rows = stagingJdbcTemplate.query(SELECT_SNAPSHOT, (rs, rowNum) -> {
            String status = rs.getString("status");
            return new PipelineRunStatusSnapshot(
                    rs.getLong("run_id"),
                    rs.getString("artifact_type"),
                    rs.getString("artifact_uid"),
                    status,
                    rs.getString("stage"),
                    rs.getInt("notices_count"),
                    result(runId, status, rs.getString("draft_json")));
        }, runId);
        return rows.stream().findFirst();
    }

    JsonNode result(Long runId, String status, String draftJson) {
        if (!STATUSES_WITH_RESULT.contains(status) || draftJson == null || draftJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(draftJson);
        } catch (JsonProcessingException e) {
            log.warn("draft_json запуска {} не является корректным JSON, result не отдаётся", runId, e);
            return null;
        }
    }
}
