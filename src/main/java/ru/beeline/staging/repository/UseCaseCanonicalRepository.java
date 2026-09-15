/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UseCaseCanonicalRepository {

    public record RequiredOperationVersionRow(Long requiredOperationId, String extUid, Long runId,
                                              Long usecaseVersionId, String productUid, String status,
                                              String branchName, Long operationVersionId, String jsonData) {
    }

    public record StepVersionRow(Long usecaseVersionId, Long callerRequiredOperationVersionId,
                                 Long calleeRequiredOperationVersionId, String extUid, String name, Integer seq,
                                 String scenarioType, String callStatus, String stepType, String branchName,
                                 String jsonData) {
    }

    private static final String INSERT_USECASE = """
            INSERT INTO staging.usecases (uid, project_code) VALUES (?, ?)
            ON CONFLICT (uid) DO NOTHING
            RETURNING id
            """;

    private static final String INSERT_USECASE_VERSION = """
            INSERT INTO staging.usecase_versions
                   (usecase_id, bi_step_version_id, ext_uid, name, project_code, branch_name, json_data)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """;

    private static final String INSERT_REQUIRED_OPERATION = """
            INSERT INTO staging.required_operations (uid, usecase_id, name, type) VALUES (?, ?, ?, ?)
            ON CONFLICT (uid) DO NOTHING
            RETURNING id
            """;

    private static final String INSERT_REQUIRED_OPERATION_VERSION = """
            INSERT INTO staging.required_operation_versions
                   (required_operation_id, ext_uid, run_id, usecase_version_id, product_uid, status,
                    branch_name, operation_version_id, json_data)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """;

    private static final String INSERT_STEP_VERSION = """
            INSERT INTO staging.usecase_step_versions
                   (usecase_version_id, required_operation_version_id, callee_required_operation_version_id,
                    ext_uid, name, seq, scenario_type, call_status, step_type, branch_name, json_data)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """;

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

    public Long insertUseCaseVersion(Long usecaseId, Long biStepVersionId, String extUid, String name,
                                     String projectCode, String branchName, String jsonData) {
        return stagingJdbcTemplate.queryForObject(INSERT_USECASE_VERSION, Long.class,
                usecaseId, biStepVersionId, extUid, name, projectCode, branchName, jsonData);
    }

    public Long findOrCreateRequiredOperation(String uid, Long usecaseId, String name, String type) {
        List<Long> inserted = stagingJdbcTemplate.queryForList(INSERT_REQUIRED_OPERATION, Long.class,
                uid, usecaseId, name, type);
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        return stagingJdbcTemplate.queryForObject("SELECT id FROM staging.required_operations WHERE uid = ?",
                Long.class, uid);
    }

    public Long insertRequiredOperationVersion(RequiredOperationVersionRow row) {
        return stagingJdbcTemplate.queryForObject(INSERT_REQUIRED_OPERATION_VERSION, Long.class,
                row.requiredOperationId(), row.extUid(), row.runId(), row.usecaseVersionId(), row.productUid(),
                row.status(), row.branchName(), row.operationVersionId(), row.jsonData());
    }

    public Long insertStepVersion(StepVersionRow row) {
        return stagingJdbcTemplate.queryForObject(INSERT_STEP_VERSION, Long.class,
                row.usecaseVersionId(), row.callerRequiredOperationVersionId(), row.calleeRequiredOperationVersionId(),
                row.extUid(), row.name(), row.seq(), row.scenarioType(), row.callStatus(), row.stepType(),
                row.branchName(), row.jsonData());
    }
}
