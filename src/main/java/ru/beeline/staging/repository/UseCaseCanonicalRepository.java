/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UseCaseCanonicalRepository {

    public record UseCaseVersionRow(Long id, Long usecaseId, String extUid, String name, String projectCode,
                                    String branchName, String jsonData) {
    }

    public record StepVersionRow(Long usecaseVersionId, Long operationVersionId, Long calleeOperationVersionId,
                                 String extUid, String name, Integer seq, String scenarioType, String callStatus,
                                 String stepType, String branchName, String jsonData) {
    }

    public record StepRow(Long id, String extUid, String name, Integer seq, String scenarioType, String callStatus,
                          String stepType, Long operationVersionId, Long calleeOperationVersionId, String jsonData,
                          String calleeOperation, String calleeInterfaceCode, String calleeContainerCode,
                          String calleeProductCode, String callerOperation, String callerInterfaceCode,
                          String callerContainerCode, String callerProductCode) {
    }

    private static final String INSERT_USECASE = """
            INSERT INTO staging.usecases (uid, project_code) VALUES (?, ?)
            ON CONFLICT (uid) DO NOTHING
            RETURNING id
            """;

    private static final String INSERT_USECASE_VERSION = """
            INSERT INTO staging.usecase_versions
                   (usecase_id, bi_step_version_id, run_id, ext_uid, name, project_code, branch_name, json_data)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """;

    private static final String INSERT_STEP_VERSION = """
            INSERT INTO staging.usecase_step_versions
                   (usecase_version_id, operation_version_id, callee_operation_version_id,
                    ext_uid, name, seq, scenario_type, call_status, step_type, branch_name, json_data)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """;

    private static final String SELECT_VERSION_BY_RUN = """
            SELECT id, usecase_id, ext_uid, name, project_code, branch_name, json_data::text AS json_data
              FROM staging.usecase_versions
             WHERE run_id = ?
             ORDER BY created_at DESC, id DESC
             LIMIT 1
            """;

    private static final String SELECT_STEPS = """
            SELECT s.id, s.ext_uid, s.name, s.seq, s.scenario_type, s.call_status, s.step_type,
                   s.operation_version_id, s.callee_operation_version_id, s.json_data::text AS json_data,
                   ov.name AS callee_operation, i.uid AS callee_interface_code,
                   c.uid AS callee_container_code, p.uid AS callee_product_code,
                   cov.name AS caller_operation, ci.uid AS caller_interface_code,
                   cc.uid AS caller_container_code, cp.uid AS caller_product_code
              FROM staging.usecase_step_versions s
              LEFT JOIN staging.operation_versions ov ON ov.id = s.callee_operation_version_id
              LEFT JOIN staging.interface_versions iv ON iv.id = ov.interface_version_id
              LEFT JOIN staging.interfaces i ON i.id = iv.interface_id
              LEFT JOIN staging.container_versions cv ON cv.id = iv.container_version_id
              LEFT JOIN staging.containers c ON c.id = cv.container_id
              LEFT JOIN staging.product_versions pv ON pv.id = cv.product_version_id
              LEFT JOIN staging.products p ON p.id = pv.product_id
              LEFT JOIN staging.operation_versions cov ON cov.id = s.operation_version_id
              LEFT JOIN staging.interface_versions civ ON civ.id = cov.interface_version_id
              LEFT JOIN staging.interfaces ci ON ci.id = civ.interface_id
              LEFT JOIN staging.container_versions ccv ON ccv.id = civ.container_version_id
              LEFT JOIN staging.containers cc ON cc.id = ccv.container_id
              LEFT JOIN staging.product_versions cpv ON cpv.id = ccv.product_version_id
              LEFT JOIN staging.products cp ON cp.id = cpv.product_id
             WHERE s.usecase_version_id = ?
             ORDER BY s.seq, s.id
            """;

    private static final String UPDATE_STEP_CALLEE = """
            UPDATE staging.usecase_step_versions
               SET callee_operation_version_id = ?,
                   call_status = ?,
                   json_data = COALESCE(json_data, '{}'::jsonb) || CAST(? AS jsonb)
             WHERE id = ?
            """;

    private static final RowMapper<StepRow> STEP_MAPPER = (rs, rowNum) -> new StepRow(
            rs.getLong("id"),
            rs.getString("ext_uid"),
            rs.getString("name"),
            (Integer) rs.getObject("seq"),
            rs.getString("scenario_type"),
            rs.getString("call_status"),
            rs.getString("step_type"),
            (Long) rs.getObject("operation_version_id"),
            (Long) rs.getObject("callee_operation_version_id"),
            rs.getString("json_data"),
            rs.getString("callee_operation"),
            rs.getString("callee_interface_code"),
            rs.getString("callee_container_code"),
            rs.getString("callee_product_code"),
            rs.getString("caller_operation"),
            rs.getString("caller_interface_code"),
            rs.getString("caller_container_code"),
            rs.getString("caller_product_code"));

    private final JdbcTemplate stagingJdbcTemplate;

    public UseCaseCanonicalRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    public Long findOrCreateUseCase(String uid, String projectCode) {
        List<Long> inserted = stagingJdbcTemplate.queryForList(INSERT_USECASE, Long.class, uid, projectCode);
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        return stagingJdbcTemplate.queryForObject("SELECT id FROM staging.usecases WHERE uid = ?", Long.class, uid);
    }

    public Long insertUseCaseVersion(Long usecaseId, Long biStepVersionId, Long runId, String extUid, String name,
                                     String projectCode, String branchName, String jsonData) {
        return stagingJdbcTemplate.queryForObject(INSERT_USECASE_VERSION, Long.class,
                usecaseId, biStepVersionId, runId, extUid, name, projectCode, branchName, jsonData);
    }

    public Long insertStepVersion(StepVersionRow row) {
        return stagingJdbcTemplate.queryForObject(INSERT_STEP_VERSION, Long.class,
                row.usecaseVersionId(), row.operationVersionId(), row.calleeOperationVersionId(),
                row.extUid(), row.name(), row.seq(), row.scenarioType(), row.callStatus(), row.stepType(),
                row.branchName(), row.jsonData());
    }

    public Optional<UseCaseVersionRow> findVersionByRunId(Long runId) {
        return stagingJdbcTemplate.query(SELECT_VERSION_BY_RUN, (rs, rowNum) -> new UseCaseVersionRow(
                rs.getLong("id"),
                rs.getLong("usecase_id"),
                rs.getString("ext_uid"),
                rs.getString("name"),
                rs.getString("project_code"),
                rs.getString("branch_name"),
                rs.getString("json_data")), runId).stream().findFirst();
    }

    public List<StepRow> findSteps(Long usecaseVersionId) {
        return stagingJdbcTemplate.query(SELECT_STEPS, STEP_MAPPER, usecaseVersionId);
    }

    public int updateStepCallee(Long stepId, Long calleeOperationVersionId, String callStatus, String jsonPatch) {
        return stagingJdbcTemplate.update(UPDATE_STEP_CALLEE,
                calleeOperationVersionId, callStatus, jsonPatch == null ? "{}" : jsonPatch, stepId);
    }
}
