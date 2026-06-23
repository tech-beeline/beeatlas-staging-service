package ru.beeline.staging.sparx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.sparx.dto.E2EScenarioMeta;

import java.util.List;

@Slf4j
@Repository
public class SparxE2ERepository {

    private static final String FIND_ALL_SCENARIOS = """
            SELECT DISTINCT
                d.ea_guid   AS uid,
                d.name      AS name,
                d.version   AS version,
                p.ea_guid   AS process_uid,
                p.name      AS process_name,
                d.notes     AS notes
            FROM t_diagram p
                JOIN t_diagramobjects odd ON odd.diagram_id = p.diagram_id
                JOIN t_object ref ON ref.object_id = odd.object_id AND ref.object_type = 'InteractionOccurrence'
                JOIN t_diagram d ON d.diagram_id::text = ref.pdata1
            WHERE p.stereotype = 'e2e_diagram'
            """;

    private final JdbcTemplate sparxJdbcTemplate;

    public SparxE2ERepository(
            @Autowired(required = false) @Qualifier("sparxJdbcTemplate") JdbcTemplate sparxJdbcTemplate) {
        this.sparxJdbcTemplate = sparxJdbcTemplate;
    }

    public List<E2EScenarioMeta> findAllScenarios() {
        if (sparxJdbcTemplate == null) {
            log.warn("Sparx datasource not configured (staging.sparx.datasource.url not set) — returning empty list");
            return List.of();
        }
        return sparxJdbcTemplate.query(FIND_ALL_SCENARIOS, (rs, rowNum) -> {
            E2EScenarioMeta meta = new E2EScenarioMeta();
            meta.setUid(rs.getString("uid"));
            meta.setName(rs.getString("name"));
            meta.setVersion(rs.getString("version"));
            meta.setProcessUid(rs.getString("process_uid"));
            meta.setProcessName(rs.getString("process_name"));
            meta.setNotes(rs.getString("notes"));
            return meta;
        });
    }
}
