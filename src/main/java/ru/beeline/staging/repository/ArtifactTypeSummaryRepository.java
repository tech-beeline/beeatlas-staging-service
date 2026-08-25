package ru.beeline.staging.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.artifacttype.ArtifactTypeSummary;

import java.util.List;

/**
 * Ported from documentation/staging-service/api/rest/GET__api_v1_artifact-types.md — keep in sync
 * with that spec.
 */
@Repository
public class ArtifactTypeSummaryRepository {

    private static final String SELECT_ARTIFACT_TYPES = """
            SELECT
                sat.id,
                sat.name,
                dt.code AS data_type_code,
                ss.code AS source_system_code,
                ss.name AS source_system_name,
                sat.created_at
            FROM staging.source_artifact_types sat
                JOIN staging.data_types dt ON dt.id = sat.data_type_id
                JOIN staging.source_systems ss ON ss.id = sat.source_system_id
            ORDER BY sat.id ASC
            """;

    private final JdbcTemplate stagingJdbcTemplate;

    public ArtifactTypeSummaryRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
    }

    public List<ArtifactTypeSummary> findAll() {
        return stagingJdbcTemplate.query(SELECT_ARTIFACT_TYPES, (rs, rowNum) -> new ArtifactTypeSummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("data_type_code"),
                rs.getString("source_system_code"),
                rs.getString("source_system_name"),
                rs.getTimestamp("created_at").toLocalDateTime()
        ));
    }
}
