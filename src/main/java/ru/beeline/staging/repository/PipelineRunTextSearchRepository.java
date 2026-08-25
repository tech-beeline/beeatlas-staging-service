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

    /**
     * count(*) OVER() rides along with the page instead of a separate COUNT query — Postgres computes
     * it over the full WHERE-filtered set before LIMIT/OFFSET are applied, so it's still the true total.
     * raw_content is joined in directly too, so the caller never needs a per-row follow-up query for it.
     */
    private static final String FIND_RUNS = """
            SELECT
                r.id,
                r.artifact_uid,
                sa.name AS artifact_name,
                r.artifact_type,
                r.status,
                r.started_at,
                r.raw_data_ref_id,
                rc.raw_content,
                count(*) OVER() AS total_count
            FROM staging.pipeline_runs r
                LEFT JOIN staging.source_artifacts sa ON sa.ext_uid = r.artifact_uid
                    AND sa.source_artifact_type_id = (
                        SELECT sat.id FROM staging.source_artifact_types sat
                        JOIN staging.data_types t ON t.id = sat.data_type_id
                        WHERE t.code = r.artifact_type
                        LIMIT 1
                    )
                LEFT JOIN staging.raw_data_refs rc ON rc.id = r.raw_data_ref_id
            """ + WHERE_CLAUSE + """
            ORDER BY r.started_at DESC, r.id DESC
            LIMIT ?
            OFFSET ?
            """;

    /**
     * Overlap join done in Postgres instead of pulling every context row (can be 100k+ per
     * raw_data_ref_id) into the JVM and scanning it once per occurrence — see idx_raw_data_contexts_byte_range.
     * Batched across the whole page in one call: ref_id travels alongside each occurrence's own
     * start/end so occurrences from different runs in the same page don't cross-match each other's contexts.
     */
    private static final String FIND_OVERLAPPING_CONTEXTS = """
            SELECT o.ord AS occurrence_index, c.id, c.position
            FROM unnest(?::bigint[], ?::bigint[], ?::bigint[]) WITH ORDINALITY AS o(ref_id, start_offset, end_offset, ord)
            JOIN staging.raw_data_contexts c
                ON c.raw_data_ref_id = o.ref_id
               AND (c.position #>> '{primary,type}') = 'byte_range'
               AND (c.position #>> '{primary,value,start_offset}')::bigint < o.end_offset
               AND (c.position #>> '{primary,value,end_offset}')::bigint > o.start_offset
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

        long[] totalCount = {0};
        List<PipelineRunRow> rows = stagingJdbcTemplate.query(FIND_RUNS, (rs, rowNum) -> {
            totalCount[0] = rs.getLong("total_count");
            return new PipelineRunRow(
                    rs.getLong("id"),
                    rs.getString("artifact_uid"),
                    rs.getString("artifact_name"),
                    rs.getString("artifact_type"),
                    rs.getString("status"),
                    rs.getTimestamp("started_at").toLocalDateTime(),
                    rs.getLong("raw_data_ref_id"),
                    rs.getBytes("raw_content"));
        }, artifactType, artifactUid, status, status, from, from, to, to, limit, offset);

        return new RunsPage(totalCount[0], rows);
    }

    /**
     * @param refIds parallel to startOffsets/endOffsets — the raw_data_ref_id each occurrence belongs to,
     *               so occurrences from different runs in the same batch only match their own contexts.
     */
    public List<OverlapHit> findOverlappingContexts(long[] refIds, long[] startOffsets, long[] endOffsets) {
        if (refIds.length == 0) {
            return List.of();
        }
        Long[] refs = java.util.Arrays.stream(refIds).boxed().toArray(Long[]::new);
        Long[] starts = java.util.Arrays.stream(startOffsets).boxed().toArray(Long[]::new);
        Long[] ends = java.util.Arrays.stream(endOffsets).boxed().toArray(Long[]::new);
        return stagingJdbcTemplate.query(FIND_OVERLAPPING_CONTEXTS,
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("bigint", refs));
                    ps.setArray(2, ps.getConnection().createArrayOf("bigint", starts));
                    ps.setArray(3, ps.getConnection().createArrayOf("bigint", ends));
                },
                (rs, rowNum) -> new OverlapHit(
                        rs.getInt("occurrence_index"),
                        new ContextRow(rs.getLong("id"), parsePosition(rs.getString("position")))));
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
                                   LocalDateTime startedAt, Long rawDataRefId, byte[] rawContent) {}

    public record RunsPage(long totalCount, List<PipelineRunRow> rows) {}

    public record ContextRow(Long id, JsonNode position) {}

    /** occurrenceIndex is 1-based, matching the ordinal position in the arrays passed to findOverlappingContexts. */
    public record OverlapHit(int occurrenceIndex, ContextRow context) {}
}
