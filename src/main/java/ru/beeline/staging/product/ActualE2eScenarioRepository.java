package ru.beeline.staging.product;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads back the current ("actual") saved state of one e2e_scenario from the staging canonical model,
 * as it is published to fdm-products. Ported from
 * documentation/staging-service/queries/get-actual-e2e-scenario.sql — keep in sync with that file.
 */
@Repository
public class ActualE2eScenarioRepository {

    private static final String FETCH_ACTUAL_SCENARIO = """
            WITH cte_artifacts AS (
                SELECT
                    a.ext_uid, a.last_loaded_ref_id AS ref_id
                FROM staging.source_artifacts a
                JOIN staging.source_artifact_types t ON t.id=a.source_artifact_type_id
                WHERE a.ext_uid=?
                    AND t.name='e2e-sequence'
            ), cte_contexts AS (
                SELECT
                    *
                FROM cte_artifacts a
                    JOIN staging.raw_data_contexts c ON c.raw_data_ref_id=a.ref_id
            ), cte_e2e AS (
                SELECT
                    v.id as e2e_version_id, v.ext_uid, v.name, v.description, b.ext_uid as bi_step_code
                FROM cte_contexts c
                    JOIN staging.e2e_scenario_versions v ON v.raw_data_context_id=c.id
                    LEFT JOIN staging.bi_step_versions b ON b.id=v.bi_step_version_id
            ), cte_operations AS (
                SELECT
                    v.*, i.ext_uid AS interface_code
                FROM cte_contexts c
                    JOIN staging.operation_versions v ON v.raw_data_context_id=c.id
                    LEFT JOIN staging.interface_versions i ON i.id=v.interface_version_id
            ), cte_api AS (
                SELECT
                    v.*, cv.ext_uid as container_code
                FROM cte_contexts c
                    JOIN staging.interface_versions v ON v.raw_data_context_id=c.id
                    LEFT JOIN staging.container_versions cv ON cv.id=v.container_version_id
            ), cte_containers AS (
                SELECT
                    v.*, p.ext_uid as product_code
                FROM cte_contexts c
                    JOIN staging.container_versions v ON v.raw_data_context_id=c.id
                    LEFT JOIN staging.product_versions p ON p.id=v.product_version_id
            ), cte_op_rel AS (
                SELECT
                    v.*, o.ext_uid AS operation_uid, r.ext_uid AS related_operation_uid
                FROM cte_contexts c
                    JOIN staging.operation_relation_versions v ON v.raw_data_context_id=c.id
                        LEFT JOIN staging.operation_versions o ON o.id=v.operation_version_id
                        LEFT JOIN staging.operation_versions r ON r.id=v.related_operation_version_id
            ), cte_products AS (
                SELECT
                    v.*
                FROM cte_contexts c
                    JOIN staging.product_versions v ON v.raw_data_context_id=c.id
            )
            SELECT
                jsonb_build_object(
                    'e2e', jsonb_build_object('uid', e2e.ext_uid,
                    'name', e2e.name,
                    'description', e2e.description,
                    'bi_step_code', e2e.bi_step_code
                    ),
                    'products', COALESCE((SELECT
                        jsonb_agg(
                            jsonb_build_object(
                                'name', p.name,
                                'code', p.ext_uid))
                        FROM cte_products p),'[]'::jsonb),
                    'containers', COALESCE((SELECT        jsonb_agg(
                            jsonb_build_object(
                                'name', c.name,
                                'code', c.ext_uid,
                                'parent_product_cmdb', c.product_code))
                        FROM cte_containers c), '[]'::jsonb),
                    'interfaces', COALESCE((SELECT        jsonb_agg(
                            jsonb_build_object(
                                'name', c.name,
                                'code', c.ext_uid,
                                'parent_container_code', c.container_code ))
                        FROM cte_api c), '[]'::jsonb),
                    'operations', COALESCE((SELECT        jsonb_agg(
                            jsonb_build_object(
                                'name', c.name,
                                'uid', c.ext_uid,
                                'interface_code', c.interface_code,
                                'sla', jsonb_build_object(
                                    'rps',c.rps,
                                    'latency', c.latency,
                                    'error_rate', c.error_rate)))
                        FROM cte_operations c), '[]'::jsonb),
                    'operation_relations', COALESCE((SELECT        jsonb_agg(
                            jsonb_build_object(
                                'operation_uid', c.operation_uid,
                                'call_order', c.call_order,
                                'stereotype', c.stereotype,
                                'related_operation_uid', c.related_operation_uid))
                        FROM cte_op_rel c), '[]'::jsonb))::text AS result
            FROM cte_e2e e2e
            """;

    private final JdbcTemplate stagingJdbcTemplate;

    public ActualE2eScenarioRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    /**
     * @return the current saved state of the e2e scenario as JSON text, or {@code null} if no
     * e2e_scenario_versions row exists yet for this artifact's latest raw_data_context (i.e. nothing to publish).
     */
    public String fetchActualScenarioRaw(String artifactUid) {
        List<String> rows = stagingJdbcTemplate.query(FETCH_ACTUAL_SCENARIO,
                (rs, rowNum) -> rs.getString("result"), artifactUid);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
