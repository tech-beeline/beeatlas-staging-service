package ru.beeline.staging.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Ported from documentation/staging-service/api/rest/GET__api_v1_pipeline-runs_search__artifactType___artifactUid_.md
 * — keep in sync with that spec. Text matching itself happens in {@link
 * ru.beeline.staging.service.PipelineRunTextSearchService} against decompressed raw_content; this
 * repository only selects the candidate runs and loads their raw data.
 */
@Repository
public class PipelineRunTextSearchRepository {

    private static final String WHERE_CLAUSE = """
            WHERE r.artifact_type = ?
                AND r.artifact_uid = ?
                AND r.raw_data_ref_id IS NOT NULL
                AND (?::text IS NULL OR r.status = ?)
                AND (?::timestamp IS NULL OR r.started_at >= ?::timestamp)
                AND (?::timestamp IS NULL OR r.started_at <= ?::timestamp)
            """;

    private static final String EXISTS_RUNS = """
            SELECT EXISTS(
                SELECT 1 FROM staging.pipeline_runs WHERE artifact_type = ? AND artifact_uid = ?
            )
            """;

    private static final String COUNT_RUNS = """
            SELECT count(*)
            FROM staging.pipeline_runs r
            """ + WHERE_CLAUSE;

    private static final String FIND_RUNS = """
            SELECT
                r.id,
                r.artifact_uid,
                sa.name AS artifact_name,
                r.artifact_type,
                r.status,
                r.started_at,
                r.raw_data_ref_id
            FROM staging.pipeline_runs r
                LEFT JOIN staging.source_artifacts sa ON sa.ext_uid = r.artifact_uid
                    AND sa.source_artifact_type_id = (
                        SELECT sat.id FROM staging.source_artifact_types sat
                        JOIN staging.data_types t ON t.id = sat.data_type_id
                        WHERE t.code = r.artifact_type
                        LIMIT 1
                    )
            """ + WHERE_CLAUSE + """
            ORDER BY r.started_at DESC, r.id DESC
            LIMIT ?
            OFFSET ?
            """;

    private static final String FIND_RAW_CONTENT = """
            SELECT raw_content FROM staging.raw_data_refs WHERE id = ?
            """;

    private static final String FIND_CONTEXTS = """
            SELECT id, position FROM staging.raw_data_contexts WHERE raw_data_ref_id = ?
            """;

    private final JdbcTemplate stagingJdbcTemplate;
    private final ObjectMapper objectMapper;

    public PipelineRunTextSearchRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate,
                                            ObjectMapper objectMapper) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean existsRuns(String artifactType, String artifactUid) {
        Boolean exists = stagingJdbcTemplate.queryForObject(EXISTS_RUNS, Boolean.class, artifactType, artifactUid);
        return Boolean.TRUE.equals(exists);
    }

    public RunsPage findRuns(String artifactType, String artifactUid, String status,
                              LocalDateTime dateFrom, LocalDateTime dateTo, int limit, int offset) {
        Timestamp from = dateFrom != null ? Timestamp.valueOf(dateFrom) : null;
        Timestamp to = dateTo != null ? Timestamp.valueOf(dateTo) : null;

        Long totalCount = stagingJdbcTemplate.queryForObject(COUNT_RUNS, Long.class,
                artifactType, artifactUid, status, status, from, from, to, to);

        List<PipelineRunRow> rows = stagingJdbcTemplate.query(FIND_RUNS, (rs, rowNum) -> new PipelineRunRow(
                rs.getLong("id"),
                rs.getString("artifact_uid"),
                rs.getString("artifact_name"),
                rs.getString("artifact_type"),
                rs.getString("status"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getLong("raw_data_ref_id")
        ), artifactType, artifactUid, status, status, from, from, to, to, limit, offset);

        return new RunsPage(totalCount != null ? totalCount : 0, rows);
    }

    public byte[] findRawContent(Long rawDataRefId) {
        List<byte[]> result = stagingJdbcTemplate.query(FIND_RAW_CONTENT,
                (rs, rowNum) -> rs.getBytes("raw_content"), rawDataRefId);
        return result.isEmpty() ? null : result.get(0);
    }

    public List<ContextRow> findContexts(Long rawDataRefId) {
        return stagingJdbcTemplate.query(FIND_CONTEXTS, (rs, rowNum) -> new ContextRow(
                rs.getLong("id"),
                parsePosition(rs.getString("position"))
        ), rawDataRefId);
    }

    private JsonNode parsePosition(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse raw_data_contexts.position JSON: " + json, e);
        }
    }

    public record PipelineRunRow(Long id, String artifactUid, String artifactName, String artifactType, String status,
                                   LocalDateTime startedAt, Long rawDataRefId) {}

    public record RunsPage(long totalCount, List<PipelineRunRow> rows) {}

    public record ContextRow(Long id, JsonNode position) {}
}
