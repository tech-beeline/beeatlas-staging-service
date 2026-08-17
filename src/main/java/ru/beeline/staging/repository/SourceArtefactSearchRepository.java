package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.search.ArtifactSearchPage;
import ru.beeline.staging.dto.search.ArtifactSearchResult;

import java.util.List;

/**
 * Ported from documentation/staging-service/api/rest/GET__api_v1_artifacts.md — keep in sync with
 * that spec.
 */
@Repository
public class SourceArtefactSearchRepository {

    private static final String WHERE_CLAUSE = """
            WHERE a.name ILIKE '%' || ? || '%'
                AND (?::integer IS NULL OR a.source_artifact_type_id = ?)
                AND (?::text IS NULL OR a.status = ?)
            """;

    private static final String COUNT_ARTIFACTS = """
            SELECT count(*)
            FROM staging.source_artifacts a
            """ + WHERE_CLAUSE;

    private static final String SEARCH_ARTIFACTS = """
            SELECT
                a.id,
                a.ext_uid,
                a.name,
                a.source_artifact_type_id,
                a.status,
                a.last_run_id,
                a.last_seen_scan_run_id,
                a.updated_at
            FROM staging.source_artifacts a
            """ + WHERE_CLAUSE + """
            ORDER BY a.updated_at DESC, a.id DESC
            LIMIT ?
            OFFSET ?
            """;

    private final JdbcTemplate stagingJdbcTemplate;

    public SourceArtefactSearchRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    public ArtifactSearchPage search(String name, Integer artifactTypeId, String status, int limit, int offset) {
        Long totalCount = stagingJdbcTemplate.queryForObject(COUNT_ARTIFACTS, Long.class,
                name, artifactTypeId, artifactTypeId, status, status);

        List<ArtifactSearchResult> results = stagingJdbcTemplate.query(SEARCH_ARTIFACTS, (rs, rowNum) ->
                new ArtifactSearchResult(
                        rs.getLong("id"),
                        rs.getString("ext_uid"),
                        rs.getString("name"),
                        rs.getLong("source_artifact_type_id"),
                        rs.getString("status"),
                        (Long) rs.getObject("last_run_id"),
                        (Long) rs.getObject("last_seen_scan_run_id"),
                        rs.getTimestamp("updated_at").toLocalDateTime(),
                        "name"
                ), name, artifactTypeId, artifactTypeId, status, status, limit, offset);

        return new ArtifactSearchPage(totalCount != null ? totalCount : 0, results);
    }
}
