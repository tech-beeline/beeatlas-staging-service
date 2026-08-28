/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.rundetails.PipelineRunDetails;
import ru.beeline.staging.dto.rundetails.PipelineStageLogDto;

import java.util.List;
import java.util.Optional;


@Repository
public class PipelineRunDetailsRepository {

    private static final String SELECT_RUN_DETAILS = """
           SELECT
                r.id AS run_id,
                COALESCE(r.parent_run_id, r.id) AS scan_run_id,
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
                -- Not b.run_id = r.id: when a run's content is unchanged, SaverStage skips creating
                -- a new batch and reuses the existing one (isAlreadyFullyProcessed) — that batch's
                -- run_id still points at whichever earlier run actually created it, so joining on
                -- run_id came up empty for every run after the first (the common case once an
                -- artifact stabilizes). Joining on the artifact's current batch reflects "what this
                -- artifact is currently saved as", which is what a run's own /details should show.
                LEFT JOIN staging.artifact_batches b
                    ON b.artifact_uid = r.artifact_uid AND b.artifact_type = r.artifact_type AND b.is_current = true
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
                rs.getLong("run_id"),
                rs.getLong("scan_run_id"),
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
