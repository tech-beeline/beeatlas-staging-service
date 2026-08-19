package ru.beeline.staging.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.rundetails.PipelineRunDetails;
import ru.beeline.staging.dto.rundetails.PipelineStageLogDto;

import java.util.List;
import java.util.Optional;

/**
 * Ported from documentation/staging-service/queries/select-pipeline-details.sql — keep in sync with
 * that file. Unlike the reference query, stages is built with explicit camelCase keys (jsonb_build_object)
 * instead of jsonb_agg(l), since a plain row-to-json cast would emit pipeline_stage_logs' snake_case
 * column names, which doesn't match the documented response shape.
 */
@Repository
public class PipelineRunDetailsRepository {

    private static final String SELECT_RUN_DETAILS = """
           SELECT
                r.id,
                r.artifact_uid,
                sa.name AS artifact_name,
                r.artifact_type,
                r.status,
                s.name AS source_name,
                r.started_at,
                r.completed_at,
                r.raw_data_ref_id,
                b.id AS batch,
                COALESCE((
                    SELECT jsonb_agg(jsonb_build_object(
                        'id', l.id,
                        'runId', l.run_id,
                        'scanRunId', l.scan_run_id,
                        'stageName', l.stage_name,
                        'status', l.status,
                        'inputData', l.input_data,
                        'outputData', l.output_data,
                        'summaryJson', l.summary_json,
                        'startedAt', l.started_at,
                        'completedAt', l.completed_at,
                        'failureReason', l.failure_reason
                    ) ORDER BY l.started_at)
                    FROM staging.pipeline_stage_logs l
                    WHERE l.run_id = r.id
                ), '[]'::jsonb) AS stages
            FROM staging.pipeline_runs r
                JOIN staging.configurations c ON c.id = r.configuration_id
                JOIN staging.source_systems s ON s.id = c.source_system_id
				JOIN staging.data_types t ON t.code=r.artifact_type
				JOIN staging.source_artifact_types sat ON sat.data_type_id=t.id
                LEFT JOIN staging.artifact_batches b ON b.run_id = r.id
                LEFT JOIN staging.source_artifacts sa ON sa.ext_uid = r.artifact_uid
					AND sa.source_artifact_type_id=sat.id
            WHERE r.id = ?
            """;

    private final JdbcTemplate stagingJdbcTemplate;
    private final ObjectMapper objectMapper;

    public PipelineRunDetailsRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate,
                                         ObjectMapper objectMapper) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<PipelineRunDetails> findById(Long runId) {
        List<PipelineRunDetails> rows = stagingJdbcTemplate.query(SELECT_RUN_DETAILS, (rs, rowNum) -> new PipelineRunDetails(
                rs.getLong("id"),
                rs.getString("artifact_uid"),
                rs.getString("artifact_name"),
                rs.getString("artifact_type"),
                rs.getString("status"),
                rs.getString("source_name"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
                (Long) rs.getObject("raw_data_ref_id"),
                rs.getObject("batch") != null ? rs.getLong("batch") : null,
                parseStages(rs.getString("stages"))
        ), runId);
        return rows.stream().findFirst();
    }

    private List<PipelineStageLogDto> parseStages(String stagesJson) {
        if (stagesJson == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(stagesJson, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, PipelineStageLogDto.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stages JSON: " + stagesJson, e);
        }
    }
}
