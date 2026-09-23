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
public class UseCaseLandscapeRepository {

    public record LandscapeOperation(Long operationVersionId, String operation, String interfaceCode,
                                     String containerCode, String productCode) {
    }

    public record LandscapeInterface(Long interfaceVersionId, String interfaceCode, String containerCode,
                                     String productCode) {
    }

    private static final String OPERATION_SELECT = """
            SELECT ov.id AS operation_version_id, ov.name AS operation_name,
                   i.uid AS interface_code, c.uid AS container_code, p.uid AS product_code
              FROM staging.operation_versions ov
              JOIN staging.interface_versions iv ON iv.id = ov.interface_version_id
              JOIN staging.interfaces i ON i.id = iv.interface_id
              LEFT JOIN staging.container_versions cv ON cv.id = iv.container_version_id
              LEFT JOIN staging.containers c ON c.id = cv.container_id
              LEFT JOIN staging.product_versions pv ON pv.id = cv.product_version_id
              LEFT JOIN staging.products p ON p.id = pv.product_id
            """;

    private static final String CURRENT_OPERATION_IN_BRANCH = """
               AND COALESCE(ov.branch_name, 'main') = ?
               AND ov.created_at = (SELECT max(o2.created_at)
                                      FROM staging.operation_versions o2
                                     WHERE o2.operation_id IS NOT DISTINCT FROM ov.operation_id
                                       AND COALESCE(o2.branch_name, 'main') = COALESCE(ov.branch_name, 'main'))
             ORDER BY ov.created_at DESC, ov.id DESC
             LIMIT 1
            """;

    private static final String FIND_OPERATION_BY_CALL = OPERATION_SELECT + """
             WHERE ov.name = ?
               AND upper(ov.json_data ->> 'type') = ?
               AND (lower(c.uid) IN (lower(?), lower(?)) OR lower(p.uid) IN (lower(?), lower(?)))
            """ + CURRENT_OPERATION_IN_BRANCH;

    private static final String FIND_OPERATION_BY_CODE = OPERATION_SELECT + """
             WHERE lower(ov.name) = lower(?)
               AND (CAST(? AS text) IS NULL OR lower(i.uid) = lower(CAST(? AS text)))
            """ + CURRENT_OPERATION_IN_BRANCH;

    private static final String FIND_OPERATIONS_BY_INTERFACE = OPERATION_SELECT + """
             WHERE lower(i.uid) = lower(?)
               AND lower(COALESCE(c.uid, '')) = lower(?)
               AND COALESCE(ov.branch_name, 'main') = ?
               AND ov.created_at = (SELECT max(o2.created_at)
                                      FROM staging.operation_versions o2
                                     WHERE o2.operation_id IS NOT DISTINCT FROM ov.operation_id
                                       AND COALESCE(o2.branch_name, 'main') = COALESCE(ov.branch_name, 'main'))
             ORDER BY ov.name, ov.id
             LIMIT ?
            """;

    private static final String FIND_INTERFACE = """
            SELECT iv.id AS interface_version_id, i.uid AS interface_code, c.uid AS container_code, p.uid AS product_code
              FROM staging.interface_versions iv
              JOIN staging.interfaces i ON i.id = iv.interface_id
              LEFT JOIN staging.container_versions cv ON cv.id = iv.container_version_id
              LEFT JOIN staging.containers c ON c.id = cv.container_id
              LEFT JOIN staging.product_versions pv ON pv.id = cv.product_version_id
              LEFT JOIN staging.products p ON p.id = pv.product_id
             WHERE lower(i.uid) = lower(?)
               AND lower(COALESCE(c.uid, '')) = lower(?)
               AND COALESCE(iv.branch_name, 'main') = ?
               AND iv.created_at = (SELECT max(i2.created_at)
                                      FROM staging.interface_versions i2
                                     WHERE i2.interface_id = iv.interface_id
                                       AND COALESCE(i2.branch_name, 'main') = COALESCE(iv.branch_name, 'main'))
             ORDER BY iv.created_at DESC, iv.id DESC
             LIMIT 1
            """;

    private static final String FIND_BI_STEP_VERSION = """
            SELECT bv.id
              FROM staging.bi_step_versions bv
              JOIN staging.bi_steps b ON b.id = bv.bi_step_id
             WHERE b.uid = ?
               AND COALESCE(bv.branch_name, 'main') = ?
             ORDER BY bv.created_at DESC, bv.id DESC
             LIMIT 1
            """;

    private static final RowMapper<LandscapeOperation> OPERATION_MAPPER = (rs, rowNum) -> new LandscapeOperation(
            rs.getLong("operation_version_id"),
            rs.getString("operation_name"),
            rs.getString("interface_code"),
            rs.getString("container_code"),
            rs.getString("product_code"));

    private static final RowMapper<LandscapeInterface> INTERFACE_MAPPER = (rs, rowNum) -> new LandscapeInterface(
            rs.getLong("interface_version_id"),
            rs.getString("interface_code"),
            rs.getString("container_code"),
            rs.getString("product_code"));

    private final JdbcTemplate stagingJdbcTemplate;

    public UseCaseLandscapeRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    public Optional<LandscapeOperation> findOperationByCall(String path, String method, String branch,
                                                            String participantAlias, String participantMnemonic) {
        return stagingJdbcTemplate.query(FIND_OPERATION_BY_CALL, OPERATION_MAPPER,
                path, method, participantAlias, participantMnemonic, participantAlias, participantMnemonic, branch)
                .stream().findFirst();
    }

    public Optional<LandscapeOperation> findOperationByCode(String operationCode, String interfaceCode, String branch) {
        return stagingJdbcTemplate.query(FIND_OPERATION_BY_CODE, OPERATION_MAPPER,
                operationCode, interfaceCode, interfaceCode, branch)
                .stream().findFirst();
    }

    public List<LandscapeOperation> findOperationsByInterface(String interfaceCode, String containerCode,
                                                              String branch, int limit) {
        return stagingJdbcTemplate.query(FIND_OPERATIONS_BY_INTERFACE, OPERATION_MAPPER,
                interfaceCode, containerCode, branch, limit);
    }

    public Optional<LandscapeInterface> findInterface(String interfaceCode, String containerCode, String branch) {
        return stagingJdbcTemplate.query(FIND_INTERFACE, INTERFACE_MAPPER, interfaceCode, containerCode, branch)
                .stream().findFirst();
    }

    public Optional<Long> findBiStepVersionId(String biStepCode, String branch) {
        return stagingJdbcTemplate.queryForList(FIND_BI_STEP_VERSION, Long.class, biStepCode, branch)
                .stream().findFirst();
    }
}
