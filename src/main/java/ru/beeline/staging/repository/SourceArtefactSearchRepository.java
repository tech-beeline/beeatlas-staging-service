package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.artifact.ArtifactByUidResult;
import ru.beeline.staging.dto.search.ArtifactSearchPage;
import ru.beeline.staging.dto.search.ArtifactSearchResult;

import java.util.List;
import java.util.Optional;

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

    private static final String FROM_CLAUSE = """
            FROM staging.source_artifacts a
                JOIN staging.source_artifact_types sat ON sat.id = a.source_artifact_type_id
                JOIN staging.source_systems ss ON ss.id = sat.source_system_id
            """;

    private static final String COUNT_ARTIFACTS = """
            SELECT count(*)
            """ + FROM_CLAUSE + WHERE_CLAUSE;

    private static final String SEARCH_ARTIFACTS = """
            SELECT
                a.id,
                a.ext_uid,
                a.name,
                a.source_artifact_type_id,
                a.status,
                ss.code AS source_code,
                ss.name AS source_name,
                a.last_run_id,
                a.last_seen_scan_run_id,
                a.updated_at
            """ + FROM_CLAUSE + WHERE_CLAUSE + """
            ORDER BY a.updated_at DESC, a.id DESC
            LIMIT ?
            OFFSET ?
            """;

    private static final String DATA_TYPE_EXISTS = """
            SELECT EXISTS(
                SELECT 1 FROM staging.data_types WHERE code = ?
            )
            """;

    private static final String FIND_BY_TYPE_AND_UID = """
            SELECT
                a.id,
                a.ext_uid,
                a.name,
                a.status,
                sat.id AS artifact_type_id,
                sat.name AS artifact_type_name,
                ss.code AS source_code,
                ss.name AS source_name,
                a.last_run_id,
                a.last_loaded_ref_id,
                a.last_seen_scan_run_id,
                a.created_at,
                a.updated_at
            FROM staging.source_artifacts a
                JOIN staging.source_artifact_types sat ON sat.id = a.source_artifact_type_id
                JOIN staging.source_systems ss ON ss.id = sat.source_system_id
                JOIN staging.data_types dt ON dt.id = sat.data_type_id
            WHERE dt.code = ? AND a.ext_uid = ?
            LIMIT 1
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
                        rs.getString("source_code"),
                        rs.getString("source_name"),
                        (Long) rs.getObject("last_run_id"),
                        (Long) rs.getObject("last_seen_scan_run_id"),
                        rs.getTimestamp("updated_at").toLocalDateTime(),
                        "name"
                ), name, artifactTypeId, artifactTypeId, status, status, limit, offset);

        return new ArtifactSearchPage(totalCount != null ? totalCount : 0, results);
    }

    /**
     * Ported from documentation/staging-service/api/rest/GET__api_v1_artifacts__artifactType___artifactUid_.md
     * — keep in sync with that spec. Resolves an identity record by (data type code, ext_uid) enriched
     * with source context (source_systems.code/name) and artifact type name (source_artifact_types.name).
     * Implemented with JdbcTemplate (not Spring Data projection) because the result is a DTO record and
     * Spring Data can't map a TupleBackedMap into a record for a native query.
     */
    public boolean dataTypeExists(String artifactType) {
        Boolean exists = stagingJdbcTemplate.queryForObject(DATA_TYPE_EXISTS, Boolean.class, artifactType);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<ArtifactByUidResult> findByTypeAndUid(String artifactType, String artifactUid) {
        List<ArtifactByUidResult> rows = stagingJdbcTemplate.query(FIND_BY_TYPE_AND_UID, (rs, rowNum) ->
                new ArtifactByUidResult(
                        rs.getLong("id"),
                        rs.getString("ext_uid"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getLong("artifact_type_id"),
                        rs.getString("artifact_type_name"),
                        rs.getString("source_code"),
                        rs.getString("source_name"),
                        (Long) rs.getObject("last_run_id"),
                        (Long) rs.getObject("last_loaded_ref_id"),
                        (Long) rs.getObject("last_seen_scan_run_id"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()
                ), artifactType, artifactUid);

        return rows.stream().findFirst();
    }
}
