/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UseCaseReconcileRepository {

    public record Candidate(Long id, String type, String status, Long operationVersionId, String branch,
                            String operationCode, String interfaceCode, String containerCode) {
    }

    private static final String SELECT_CANDIDATES = """
            SELECT rov.id, ro.type, rov.status, rov.operation_version_id,
                   COALESCE(rov.branch_name, 'main') AS branch,
                   rov.json_data ->> 'operation_code' AS operation_code,
                   rov.json_data ->> 'interface_code' AS interface_code,
                   rov.json_data ->> 'container_code' AS container_code
              FROM staging.required_operation_versions rov
              JOIN staging.required_operations ro ON ro.id = rov.required_operation_id
             ORDER BY rov.id
            """;

    private static final String MARK_MATCHED = """
            UPDATE staging.required_operation_versions
               SET status = 'matched', operation_version_id = CAST(? AS bigint), updated_at = now()
             WHERE id = ?
               AND (status <> 'matched' OR operation_version_id IS DISTINCT FROM CAST(? AS bigint))
            """;

    private final JdbcTemplate stagingJdbcTemplate;

    public UseCaseReconcileRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    public List<Candidate> findCandidates() {
        return stagingJdbcTemplate.query(SELECT_CANDIDATES, (rs, rowNum) -> new Candidate(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getObject("operation_version_id", Long.class),
                rs.getString("branch"),
                rs.getString("operation_code"),
                rs.getString("interface_code"),
                rs.getString("container_code")));
    }

    public int markMatched(Long id, Long operationVersionId) {
        return stagingJdbcTemplate.update(MARK_MATCHED, operationVersionId, id, operationVersionId);
    }
}
