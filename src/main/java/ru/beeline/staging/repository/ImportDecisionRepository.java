/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.usecase.ImportDecision;

import java.util.List;

@Repository
public class ImportDecisionRepository {

    private static final String SELECT_BY_RUN = """
            SELECT id, run_id, part_id, decision_type, target_json::text AS target_json,
                   new_request_json::text AS new_request_json
              FROM staging.import_decisions
             WHERE run_id = ?
             ORDER BY id
            """;

    private static final String UPSERT = """
            INSERT INTO staging.import_decisions
                   (run_id, part_id, decision_type, target_json, new_request_json, created_at, updated_at)
            VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), now(), now())
            ON CONFLICT (run_id, part_id) DO UPDATE
               SET decision_type = EXCLUDED.decision_type,
                   target_json = EXCLUDED.target_json,
                   new_request_json = EXCLUDED.new_request_json,
                   updated_at = now()
            """;

    private final JdbcTemplate stagingJdbcTemplate;

    public ImportDecisionRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    public List<ImportDecision> findByRunId(Long runId) {
        return stagingJdbcTemplate.query(SELECT_BY_RUN, (rs, rowNum) -> new ImportDecision(
                rs.getLong("id"),
                rs.getLong("run_id"),
                rs.getString("part_id"),
                rs.getString("decision_type"),
                rs.getString("target_json"),
                rs.getString("new_request_json")), runId);
    }

    public void upsert(Long runId, String partId, String decisionType, String targetJson, String newRequestJson) {
        stagingJdbcTemplate.update(UPSERT, runId, partId, decisionType, targetJson, newRequestJson);
    }
}
