package ru.beeline.staging.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.search.NoticeSearchPage;
import ru.beeline.staging.dto.search.NoticeSearchResult;

import java.util.List;

/**
 * Ported from documentation/staging-service/api/rest/GET__api_v1_search_notices__runId_.md — keep in
 * sync with that spec. Table name is staging.raw_data_contexts (plural) — the actual name per
 * migration V0001, as noted in the spec.
 */
@Repository
public class NoticeSearchRepository {

    private static final String WHERE_CLAUSE = """
            WHERE rdc.raw_data_ref_id = ?
                AND (
                    nt.code ILIKE '%' || ? || '%'
                    OR nt.description ILIKE '%' || ? || '%'
                    OR an.details ILIKE '%' || ? || '%'
                    OR rdc.position::text ILIKE '%' || ? || '%'
                )
            """;

    private static final String FROM_CLAUSE = """
            FROM staging.artifact_notices an
                JOIN staging.notice_types nt ON nt.id = an.notice_type_id
                JOIN staging.raw_data_contexts rdc ON rdc.id = an.raw_data_context_id
            """;

    private static final String COUNT_NOTICES = "SELECT count(*)\n" + FROM_CLAUSE + WHERE_CLAUSE;

    private static final String SEARCH_NOTICES = """
            SELECT
                an.id,
                an.notice_type_id,
                nt.code,
                nt.level,
                nt.category,
                nt.description,
                an.details,
                rdc.raw_data_ref_id,
                an.raw_data_context_id,
                rdc.position,
                an.created_at,
                CASE
                    WHEN nt.code ILIKE '%' || ? || '%' THEN 'code'
                    WHEN nt.description ILIKE '%' || ? || '%' THEN 'description'
                    WHEN an.details ILIKE '%' || ? || '%' THEN 'details'
                    ELSE 'position'
                END AS found_in
            """ + FROM_CLAUSE + WHERE_CLAUSE + """
            ORDER BY an.created_at DESC, an.id DESC
            LIMIT ?
            OFFSET ?
            """;

    private final JdbcTemplate stagingJdbcTemplate;
    private final ObjectMapper objectMapper;

    public NoticeSearchRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate,
                                   ObjectMapper objectMapper) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public NoticeSearchPage search(Long rawDataRefId, String text, int limit, int offset) {
        Long totalCount = stagingJdbcTemplate.queryForObject(COUNT_NOTICES, Long.class,
                rawDataRefId, text, text, text, text);

        List<NoticeSearchResult> results = stagingJdbcTemplate.query(SEARCH_NOTICES, (rs, rowNum) ->
                new NoticeSearchResult(
                        rs.getLong("id"),
                        rs.getLong("notice_type_id"),
                        rs.getString("code"),
                        rs.getString("level"),
                        rs.getString("category"),
                        rs.getString("description"),
                        rs.getString("details"),
                        rs.getLong("raw_data_ref_id"),
                        rs.getLong("raw_data_context_id"),
                        parsePosition(rs.getString("position")),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getString("found_in")
                ), text, text, text, rawDataRefId, text, text, text, text, limit, offset);

        return new NoticeSearchPage(totalCount != null ? totalCount : 0, results);
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
}
