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

    // Full raw export of one e2e scenario: entrance_diagram_uid/diagrams/objects/systems/containers/interfaces/operations,
    // no collapsing of internal calls — that happens downstream in ScenarioDecomposer.
    private static final String FETCH_SCENARIO_RAW = """
            WITH RECURSIVE cte_scenario AS (
                    	SELECT
                    		diagram_id as diagram_id,
                    		ea_guid as uid,
                    		name,
                    		0 as object_id
                    	FROM t_diagram WHERE ea_guid=?
                    ),
                    cte_c4_api AS (
                    	-- Two ways an API component can be wired to its owning softwareSystem:
                    	SELECT
                    		sys.object_id as system_id,
                    		cn.object_id AS container_id,
                    		cn.alias AS container_code,
                    		api.object_id AS api_id,
                    		api.alias AS code,
                    		'structurizr' AS source
                    	FROM t_object sys
                    		JOIN t_connector r ON r.start_object_id=sys.object_id AND r.connector_type='Realisation'
                    		JOIN t_object cn ON cn.object_id=r.end_object_id\s
                    			AND cn.stereotype='C4_Container'
                    		LEFT JOIN t_connector r2 ON r2.start_object_id=cn.object_id AND r.connector_type='Realisation'
                    		LEFT JOIN t_object api ON api.object_id=r2.end_object_id
                    	WHERE sys.stereotype='softwareSystem'
                    	UNION
                    	-- ...or a ProvidedInterface hanging directly off the softwareSystem, with no C4_Container
                    	-- in between — synthesize a virtual container + interface code, since there's neither a
                    	-- real container nor an api-side alias to read.
                    	SELECT
                    		app.object_id AS system_id,
                    		p.object_id AS container_id,
                    		'provided.'::varchar || LOWER(app.alias),
                    		i.object_id AS api_id,
                    		LOWER(i.ea_guid) || '.provided.'::varchar || LOWER(app.alias) AS code,
                    		'manual'
                    	FROM t_object app
                    		JOIN t_object p ON p.parentid=app.object_id AND p.object_type='ProvidedInterface'
                    		JOIN t_object i ON i.object_id=p.classifier
                    	WHERE app.stereotype='softwareSystem'
                    ),
                    cte_diagram_link AS
                    (
                    	SELECT\s
                    		od.diagram_id,\s
                    		o.object_id,\s
                    		d.diagram_id AS child_diagram_id,
                    		d.ea_guid,
                    		d.name,
                    		d.version,
                    		d.author,
                    		d.createddate,
                    		d.modifieddate,
                    		d.notes
                    	FROM t_xref x
                    		JOIN t_object o ON o.ea_guid=x.client
                    		JOIN t_diagram d ON d.ea_guid=x.supplier AND d.diagram_type='Sequence'
                    		JOIN t_diagramobjects od ON od.object_id=o.object_id AND od.diagram_id <> d.diagram_id
                    	WHERE x.name='DefaultDiagram'
                    ), cte_diagrams AS
                    (
                    	SELECT
                    		diagram_id,
                    		uid,
                    		name,
                    		object_id
                    	FROM cte_scenario

                    	UNION DISTINCT
                    	SELECT
                    		r.child_diagram_id,\s
                    		r.ea_guid,
                    		r.name,
                    		r.object_id
                    	FROM cte_diagram_link r
                    		JOIN cte_diagrams d on d.diagram_id=r.diagram_id\s
                    ), cte_all_diagrams AS (
                        SELECT DISTINCT ON (cd.diagram_id)
                            d.diagram_id,
                            d.ea_guid,
                            d.name,
                            d.createddate AS created_at,
                            d.modifieddate AS modified_at,
                            d.author,
                            d.version,
                            d.notes
                        FROM cte_diagrams cd
                        JOIN t_diagram d ON d.diagram_id = cd.diagram_id
                    ), cte_diagram_messages AS (
                        SELECT
                    		c.diagramid AS diagram_id,
                            c.ea_guid AS uid,
                            c.name,
                            c.start_object_id,
                            c.end_object_id,
                            c.stereotype,
                            c.seqno,
                    		c.pdata1,
                    		c.pdata4,
                            -- operation_guid из t_connectortag
                            (SELECT ct.value
                             FROM t_connectortag ct
                             WHERE ct.elementid = c.connector_id
                               AND ct.property = 'operation_guid'
                             LIMIT 1) AS operation_guid,
                            -- Связь с диаграммой
                            c.diagramid,
                    		l.ea_guid as linked_diagram_uid
                        FROM t_connector c
                    		LEFT JOIN cte_diagram_link l ON l.object_id=c.end_object_id
                        WHERE c.diagramid IN (SELECT diagram_id FROM cte_diagrams)
                          AND c.connector_type = 'Sequence'
                    ),
                    cte_objects AS (
                        SELECT DISTINCT
                            od.object_id AS id,
                            o.name,
                            COALESCE(o.stereotype, o.object_type) AS type,
                            o.alias,
                    		CASE
                    			WHEN o.stereotype='softwareSystem' THEN o.object_id
                    			ELSE COALESCE(api.system_id, cn.system_id, p.object_id)
                    		END AS system_id
                        FROM t_diagramobjects od
                        	JOIN t_object o ON o.object_id = od.object_id
                    		LEFT JOIN t_object p ON p.object_id=o.parentid AND o.object_type='ProvidedInterface'
                    		LEFT JOIN cte_c4_api api ON api.api_id=od.object_id
                    		LEFT JOIN cte_c4_api cn ON cn.container_id=od.object_id
                        WHERE od.diagram_id IN (SELECT diagram_id FROM cte_diagrams)
                    ), cte_operations AS (
                    	SELECT
                    		ea_guid AS uid,
                    		name,
                    		object_id AS interface_id,
                    		COALESCE(
                    			(SELECT jsonb_agg(
                    				jsonb_build_object('property', tg.property, 'value', COALESCE(tg.notes,tg.value)))\s
                    			FROM t_operationtag tg\s
                    			WHERE tg.elementid=operationid AND VALUE IS NOT NULL)
                    			,'[]'::jsonb) AS tags
                    	FROM t_operation
                    	WHERE ea_guid IN (SELECT DISTINCT operation_guid FROM cte_diagram_messages)
                    ), cte_interfaces AS (
                    	SELECT DISTINCT\s
                    		i.object_id AS id,\s
                    		api.code AS code,\s
                    		i.name,
                    		i.createddate AS created_at,
                    		i.modifieddate AS modified_at,
                    		i.author,
                    		i.version,
                    		api.system_id,
                    		api.container_id,
                    		api.source,
                    		(
                    			SELECT DISTINCT jsonb_agg(jsonb_build_object(
                    				'property', t.property,
                    				'value', COALESCE( t.notes, t.value)
                    			)) FROM t_objectproperties t
                    		WHERE t.object_id=api.api_id OR (t.object_id=api.container_id)) AS tags
                    	FROM cte_operations o
                    		JOIN t_object i ON i.object_id=o.interface_id
                    		JOIN cte_c4_api api ON api.api_id=o.interface_id
                    ), cte_diagram_detail AS (
                    	SELECT
                    		d.*,
                    		dd.notes,
                    		dd.version,
                    		dd.author,
                    		(
                    			SELECT\s
                    				jsonb_agg(m)\s
                    			FROM cte_diagram_messages m WHERE m.diagram_id=d.diagram_id) AS messages
                    	FROM cte_diagrams d
                    	JOIN t_diagram dd ON dd.diagram_id=d.diagram_id
                    ), cte_systems AS (
                    	SELECT DISTINCT
                    		s.object_id AS id,
                    		s.name,
                    		s.alias AS code
                    	FROM cte_objects o
                    	JOIN t_object s ON s.object_id=o.system_id
                    	UNION
                    	-- A container's owning system isn't necessarily drawn on the diagram itself (only its
                    	-- interface/operation is) — pull in every system that backs a used container too, so
                    	-- containers[].system_code always has a matching entry in systems[].
                    	SELECT DISTINCT
                    		sys.object_id AS id,
                    		sys.name,
                    		sys.alias AS code
                    	FROM cte_c4_api api
                    	JOIN cte_interfaces i ON i.id=api.api_id
                    	JOIN t_object sys ON sys.object_id=api.system_id
                    ), cte_containers AS (
                    	SELECT DISTINCT
                    		s.object_id AS id,
                    		s.name,
                    		o.container_code AS code,
                    		o.system_id,
                    		sys.alias AS system_code
                    	FROM cte_c4_api o
                    	JOIN cte_interfaces i ON i.id=o.api_id
                    	JOIN t_object s ON s.object_id=o.container_id
                    	JOIN t_object sys ON sys.object_id=o.system_id
                    )
                    SELECT (jsonb_build_object(
                    	'entrance_diagram_uid', (SELECT uid FROM cte_scenario),
                    	'diagrams', COALESCE((SELECT jsonb_agg(d) FROM cte_diagram_detail d),'[]'::jsonb ),
                    	'objects', COALESCE((SELECT jsonb_agg(o) FROM cte_objects o),'[]'::jsonb),
                    	'systems' ,COALESCE((SELECT jsonb_agg(s) FROM cte_systems s),'[]'::jsonb),
                    	'containers', COALESCE((SELECT jsonb_agg(c) FROM cte_containers c),'[]'::jsonb),
                    	'interfaces', COALESCE((SELECT jsonb_agg(i) FROM cte_interfaces i),'[]'::jsonb),
                    	'operations', COALESCE((SELECT jsonb_agg(o) FROM cte_operations o),'[]'::jsonb)
                    ))::text
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

    /**
     * Full raw export of one e2e scenario (entrance_diagram_uid/diagrams/objects/systems/containers/interfaces/operations),
     * straight from Sparx EA — no collapsing. Returns the jsonb payload as text (Postgres builds the JSON
     * server-side); {@code null} if the datasource isn't configured.
     */
    public String fetchScenarioRaw(String entranceDiagramUid) {
        if (sparxJdbcTemplate == null) {
            log.warn("Sparx datasource not configured (staging.sparx.datasource.url not set) — cannot fetch uid={}", entranceDiagramUid);
            return null;
        }
        return sparxJdbcTemplate.queryForObject(FETCH_SCENARIO_RAW, String.class, entranceDiagramUid);
    }
}
