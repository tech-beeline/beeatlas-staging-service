package ru.beeline.staging.sparx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.sparx.dto.MetricQueriesSourceMeta;

import java.util.List;
import java.util.Optional;

/**
 * Ported from documentation/staging-service/source-artefacts/metric-queries/metric-queries-preadapter-spec.md
 * §5.3 — keep in sync with that spec. Objects in Sparx EA carrying property 'api-metric-template'
 * (a Grafana dashboard URL).
 */
@Slf4j
@Repository
public class SparxMetricQueriesRepository {

    private static final String SELECT_COLUMNS = """
            SELECT DISTINCT
                COALESCE(o.alias, o.ea_guid) AS uid,
                o.name                       AS name,
                o.stereotype                 AS stereotype,
                o.object_type                AS object_type,
                prop.value                   AS api_metric_template
            FROM t_object o
                JOIN t_objectproperties prop
                  ON prop.object_id = o.object_id
                 AND prop.property  = 'api-metric-template'
            WHERE prop.value IS NOT NULL
            """;

    private static final String FIND_ALL = SELECT_COLUMNS;

    private static final String FIND_BY_UID = SELECT_COLUMNS + " AND COALESCE(o.alias, o.ea_guid) = ?";

    private final JdbcTemplate sparxJdbcTemplate;

    public SparxMetricQueriesRepository(
            @Autowired(required = false) @Qualifier("sparxJdbcTemplate") JdbcTemplate sparxJdbcTemplate) {
        this.sparxJdbcTemplate = sparxJdbcTemplate;
    }

    public List<MetricQueriesSourceMeta> findAll() {
        if (sparxJdbcTemplate == null) {
            log.warn("Sparx datasource not configured (staging.sparx.datasource.url not set) — returning empty list");
            return List.of();
        }
        return sparxJdbcTemplate.query(FIND_ALL, SparxMetricQueriesRepository::mapRow);
    }

    /**
     * Re-fetches a single object's metadata by uid. Used by {@code MetricQueriesAdapter}, since
     * pre-adapter-provided metadata isn't currently threaded through Camunda process variables to
     * the adapter stage (AdapterWorker calls {@code adapter.load(uid, sourceId, null)}) — same
     * "re-fetch by uid" pattern as StructurizrSequenceAdapter uses for fdm-products.
     */
    public Optional<MetricQueriesSourceMeta> findByUid(String uid) {
        if (sparxJdbcTemplate == null) {
            log.warn("Sparx datasource not configured (staging.sparx.datasource.url not set) — cannot fetch uid={}", uid);
            return Optional.empty();
        }
        List<MetricQueriesSourceMeta> rows = sparxJdbcTemplate.query(FIND_BY_UID, SparxMetricQueriesRepository::mapRow, uid);
        return rows.stream().findFirst();
    }

    private static MetricQueriesSourceMeta mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        MetricQueriesSourceMeta meta = new MetricQueriesSourceMeta();
        meta.setUid(rs.getString("uid"));
        meta.setName(rs.getString("name"));
        meta.setStereotype(rs.getString("stereotype"));
        meta.setObjectType(rs.getString("object_type"));
        meta.setApiMetricTemplate(rs.getString("api_metric_template"));
        return meta;
    }
}
