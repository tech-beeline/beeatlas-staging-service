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
			          d.notes     AS notes
			      FROM t_diagram p
			          JOIN t_diagramobjects odd ON odd.diagram_id = p.diagram_id
			          JOIN t_object ref ON ref.object_id = odd.object_id AND ref.object_type = 'InteractionOccurrence'
			          JOIN t_diagram d ON CAST(d.diagram_id AS text) = ref.pdata1
			      WHERE p.stereotype = 'e2e_diagram'
			      """;

	// Full raw export of one e2e scenario:
	// entrance_diagram_uid/diagrams/objects/systems/containers/interfaces/operations,
	// no collapsing of internal calls — that happens downstream in
	// ScenarioDecomposer.
	private static final String FETCH_SCENARIO_RAW = """
									            WITH RECURSIVE cte_scenario AS (
									                    	SELECT
									                    		diagram_id as diagram_id,
									                    		ea_guid as uid,
									                    		name,
									                    		0 as object_id
									                    	FROM t_diagram WHERE ea_guid=?
-- Рекурсивный обход иерархии ProvidedInterface (classifier-цепочка).
), cte_api_parent AS (
    SELECT object_id AS child_id,
        object_id,
        ea_guid
    FROM t_object
    WHERE object_type = 'Interface'
    UNION
    SELECT p.child_id,
        o.object_id,
        p.ea_guid
    FROM cte_api_parent p
        JOIN t_connector r ON r.end_object_id = p.object_id
            AND r.connector_type = 'Generalization'
        JOIN t_object o ON o.object_id = r.start_object_id
),
-- Связь "объект -> дочерняя Sequence-диаграмма" через xref DefaultDiagram.
cte_diagram_link AS (
    SELECT od.diagram_id,
        o.object_id,
        d.diagram_id AS child_diagram_id,
        d.ea_guid,
        d.name,
        d.version,
        d.author,
        d.createddate,
        d.modifieddate,
        d.notes
    FROM t_xref x
        JOIN t_object o ON o.ea_guid = x.client
        JOIN t_diagram d ON d.ea_guid = x.supplier
            AND d.diagram_type = 'Sequence'
        JOIN t_diagramobjects od ON od.object_id = o.object_id
            AND od.diagram_id <> d.diagram_id
    WHERE x.name = 'DefaultDiagram'
),
-- Рекурсивный сбор всех диаграмм сценария.
cte_diagrams AS (
    SELECT diagram_id,
        uid,
        name,
        object_id
    FROM cte_scenario
    UNION
    SELECT r.child_diagram_id,
        r.ea_guid,
        r.name,
        r.object_id
    FROM cte_diagram_link r
        JOIN cte_diagrams d ON d.diagram_id = r.diagram_id
),
cte_all_diagrams AS (
    SELECT DISTINCT ON (cd.diagram_id) d.diagram_id,
        d.ea_guid,
        d.name,
        d.createddate AS created_at,
        d.modifieddate AS modified_at,
        d.author,
        d.version,
        d.notes
    FROM cte_diagrams cd
        JOIN t_diagram d ON d.diagram_id = cd.diagram_id
),
-- Сообщения (Sequence-коннекторы) диаграмм сценария.
cte_diagram_messages AS (
    SELECT DISTINCT c.diagramid AS diagram_id,
        c.ea_guid AS uid,
        c.name,
        c.start_object_id,
        c.end_object_id,
        c.stereotype,
        c.seqno,
        c.pdata1,
        c.pdata4,
        -- operation_guid из t_connectortag
        (
            SELECT ct.value
            FROM t_connectortag ct
            WHERE ct.elementid = c.connector_id
                AND ct.property = 'operation_guid'
            LIMIT 1
        ) AS operation_guid,
        -- Связь с дочерней диаграммой
        l.ea_guid AS linked_diagram_uid
    FROM t_connector c
        JOIN t_diagramobjects so ON so.object_id = c.start_object_id
            AND so.diagram_id = c.diagramid
        JOIN t_diagramobjects eo ON eo.object_id = c.end_object_id
            AND eo.diagram_id = c.diagramid
        LEFT JOIN cte_diagram_link l ON l.object_id = c.end_object_id
    WHERE c.diagramid IN (
            SELECT diagram_id
            FROM cte_diagrams
        )
        AND c.connector_type = 'Sequence'
),
-- Два способа привязки API-компонента к владеющему softwareSystem:
-- structurizr-путь имеет приоритет над manual-путём.
cte_api_raw AS (
    SELECT sys.object_id AS system_id,
        cn.object_id AS container_id,
        cn.alias AS container_code,
        api.object_id AS api_id,
        api.alias AS code,
        'structurizr' AS source,
        0 AS source_priority -- 0 = higher priority
    FROM t_object sys
        JOIN t_connector r ON r.start_object_id = sys.object_id
            AND r.connector_type = 'Realisation'
        JOIN t_object cn ON cn.object_id = r.end_object_id
            AND cn.stereotype = 'C4_Container'
        LEFT JOIN t_connector r2 ON r2.start_object_id = cn.object_id
            AND r2.connector_type = 'Realisation'
        LEFT JOIN t_object api ON api.object_id = r2.end_object_id
    WHERE sys.stereotype = 'softwareSystem'
    UNION ALL
    SELECT app.object_id AS system_id,
        p.object_id AS container_id,
        p.ea_guid || '.' || LOWER(app.alias),
        i.child_id AS api_id,
        LOWER(i.ea_guid) || '.' || p.ea_guid || '.' || LOWER(app.alias) AS code,
        'manual' AS source,
        1 AS source_priority
    FROM t_object app
        JOIN t_object p ON p.parentid = app.object_id
            AND p.object_type = 'ProvidedInterface'
        JOIN cte_api_parent i ON i.object_id = p.classifier
        JOIN cte_diagram_messages m ON m.end_object_id = p.object_id
    WHERE app.stereotype = 'softwareSystem'
    UNION ALL
    SELECT app.object_id AS system_id,
        p.object_id AS container_id,
        p.ea_guid || '.' || LOWER(app.alias),
        i.child_id AS api_id,
        LOWER(i.ea_guid) || '.' || p.ea_guid || '.' || LOWER(app.alias) AS code,
        'manual' AS source,
        2 AS source_priority
    FROM t_object app
        JOIN t_object p ON p.parentid = app.object_id
            AND p.object_type = 'ProvidedInterface'
        JOIN cte_diagram_messages m ON m.end_object_id = p.object_id
        JOIN t_operation o ON o.ea_guid = m.operation_guid
        JOIN cte_api_parent i ON i.object_id = o.object_id
    WHERE app.stereotype = 'softwareSystem'
    UNION ALL
    SELECT app.object_id AS system_id,
        app.object_id AS container_id,
        app.ea_guid || '.' || LOWER(app.alias),
        i.child_id AS api_id,
        LOWER(i.ea_guid) || '.' || app.ea_guid || '.' || LOWER(app.alias) AS code,
        'manual' AS source,
        3 AS source_priority
    FROM t_object app
        JOIN cte_diagram_messages m ON m.end_object_id = app.object_id
        JOIN t_operation o ON o.ea_guid = m.operation_guid
        JOIN cte_api_parent i ON i.object_id = o.object_id
    WHERE app.stereotype = 'softwareSystem'
),
cte_api AS (
    -- Дедупликация по api_id: structurizr (приоритет 0) над manual (приоритет 1).
    SELECT *
    FROM (
            SELECT *,
                ROW_NUMBER() OVER (
                    PARTITION BY api_id
                    ORDER BY source_priority ASC
                ) AS rn
            FROM cte_api_raw
        ) ranked
    WHERE rn = 1
),
cte_objects AS (
    SELECT DISTINCT od.object_id AS id,
        o.name,
        COALESCE(o.stereotype, o.object_type) AS type,
        o.alias,
        CASE
            WHEN o.stereotype = 'softwareSystem' THEN o.object_id
            ELSE COALESCE(api.system_id, cn.system_id, p.object_id)
        END AS system_id
    FROM t_diagramobjects od
        JOIN t_object o ON o.object_id = od.object_id
        LEFT JOIN t_object p ON p.object_id = o.parentid
            AND o.object_type = 'ProvidedInterface'
        LEFT JOIN cte_api api ON api.api_id = od.object_id
        LEFT JOIN cte_api cn ON cn.container_id = od.object_id
    WHERE od.diagram_id IN (
            SELECT diagram_id
            FROM cte_diagrams
        )
),
-- Кандидаты C4-методов: один ряд на (операция, C4-метод), сматченный по имени.
-- Используется единым источником и для построения operations[].c4_methods[],
-- и для набора интерфейсов C4-методов (cte_interface_ids).
cte_c4_methods AS (
    SELECT ap.api_id AS operation_interface_id,
        ap.system_id AS operation_system_id,
		o.operationid AS manual_operation_id,
        ao.ea_guid AS uid,
        a.api_id AS interface_id,
        a.code AS interface_code,
        COALESCE(
            (
                SELECT jsonb_agg(
                        jsonb_build_object(
                            'property',
                            tg.property,
                            'value',
                            COALESCE(tg.notes, tg.value)
                        )
                        ORDER BY tg.property,
                            tg.value
                    )
                FROM t_operationtag tg
                WHERE tg.elementid = ao.operationid
                    AND VALUE IS NOT NULL
            ),
            '[]'::jsonb
        ) AS tags
    FROM t_operation o
        JOIN cte_api ap ON ap.api_id = o.object_id AND ap.source_priority<>0
        JOIN cte_api a ON a.system_id = ap.system_id AND a.source_priority=0
        JOIN t_operation ao ON ao.object_id = a.api_id
            AND LOWER(ao.name) = LOWER(o.name)
            AND ao.operationid <> o.operationid
    WHERE o.ea_guid IN (
            SELECT DISTINCT operation_guid
            FROM cte_diagram_messages
        )
),
cte_operations AS (
    SELECT ea_guid AS uid,
        o.name,
        o.object_id AS interface_id,
        COALESCE(
            (
                SELECT jsonb_agg(
                        jsonb_build_object(
                            'property',
                            tg.property,
                            'value',
                            COALESCE(tg.notes, tg.value)
                        )
                        ORDER BY tg.property,
                            tg.value
                    )
                FROM t_operationtag tg
                WHERE tg.elementid = o.operationid
                    AND VALUE IS NOT NULL
            ),
            '[]'::jsonb
        ) AS tags,
        -- C4-методы операции (агрегация кандидатов из cte_c4_methods)
        COALESCE(
            (
                SELECT jsonb_agg(
                        jsonb_build_object(
                            'uid',
                            m.uid,
                            'interface_id',
                            m.interface_id,
                            'interface_code',
                            m.interface_code,
                            'tags',
                            m.tags
                        )
                    )
                FROM cte_c4_methods m
                WHERE m.manual_operation_id = o.operationid
            ),
            '[]'::jsonb
        ) AS c4_methods
    FROM t_operation o
        JOIN cte_api ap ON ap.api_id = o.object_id
    WHERE o.ea_guid IN (
            SELECT DISTINCT operation_guid
            FROM cte_diagram_messages
        )
),
-- Единый набор id интерфейсов: первичные интерфейсы операций + интерфейсы C4-методов.
cte_interface_ids AS (
    SELECT DISTINCT interface_id
    FROM cte_operations
    UNION
    SELECT DISTINCT interface_id
    FROM cte_c4_methods
),
cte_tags AS (
    SELECT pi.object_id AS container_id,
        i.object_id AS api_id,
        t.*
    FROM t_object i
        LEFT JOIN t_object pi ON i.object_id = pi.classifier
        JOIN t_objectproperties t ON t.object_id = pi.object_id
            OR t.object_id = i.object_id
    WHERE pi.object_type = 'ProvidedInterface'
    UNION
    SELECT i.id,
        i.id,
        t.*
    FROM cte_objects i
        JOIN t_objectproperties t ON t.object_id = i.id
),
cte_interfaces_raw AS (
    SELECT i.object_id AS id,
        api.code AS code,
        i.name,
        i.createddate AS created_at,
        i.modifieddate AS modified_at,
        i.author,
        i.version,
        api.system_id,
        api.container_id,
        api.source,
        (
            SELECT DISTINCT jsonb_agg(
                    jsonb_build_object(
                        'property',
                        t.property,
                        'value',
                        COALESCE(t.notes, t.value)
                    )
                    ORDER BY property,
                        value
                )
            FROM cte_tags t
            WHERE t.api_id = api.api_id
                OR t.container_id = api.container_id
                OR t.api_id = api.system_id
        ) AS tags
    FROM cte_interface_ids ii
        JOIN t_object i ON i.object_id = ii.interface_id
        LEFT JOIN cte_api api ON api.api_id = ii.interface_id
),
cte_interfaces AS (
    -- G16: дедупликация интерфейсов по id (interface_id), сохраняется первый
    SELECT DISTINCT ON (id) id,
        code,
        name,
        created_at,
        modified_at,
        author,
        version,
        system_id,
        container_id,
        source,
        tags
    FROM cte_interfaces_raw
    ORDER BY id
),
cte_diagram_detail AS (
    SELECT d.*,
        dd.notes,
        dd.version,
        dd.author,
        (
            SELECT jsonb_agg(
                    m
                    ORDER BY m.seqno
                )
            FROM cte_diagram_messages m
            WHERE m.diagram_id = d.diagram_id
        ) AS messages
    FROM cte_diagrams d
        JOIN t_diagram dd ON dd.diagram_id = d.diagram_id
),
-- Владельцы интерфейсов (актуально в т.ч. для интерфейсов C4-методов,
-- т.к. cte_interfaces теперь содержит и их).
cte_systems AS (
    SELECT DISTINCT s.object_id AS id,
        s.name,
        s.alias AS code
    FROM cte_interfaces o
        JOIN t_object s ON s.object_id = o.system_id
            AND s.stereotype = 'softwareSystem'
),
cte_containers AS (
    SELECT DISTINCT s.object_id AS id,
        s.name,
        o.container_code AS code,
        o.system_id,
        sys.alias AS system_code
    FROM cte_api o
        JOIN cte_interfaces i ON i.id = o.api_id
        JOIN t_object s ON s.object_id = o.container_id
        JOIN t_object sys ON sys.object_id = o.system_id
)
SELECT jsonb_build_object(
        'entrance_diagram_uid',
        (
            SELECT uid
            FROM cte_scenario
        ),
        'diagrams',
        COALESCE(
            (
                SELECT jsonb_agg(
                        d
                        ORDER BY d.diagram_id
                    )
                FROM cte_diagram_detail d
            ),
            '[]'::jsonb
        ),
        'objects',
        COALESCE(
            (
                SELECT jsonb_agg(
                        o
                        ORDER BY o.id
                    )
                FROM cte_objects o
            ),
            '[]'::jsonb
        ),
        'systems',
        COALESCE(
            (
                SELECT jsonb_agg(
                        s
                        ORDER BY s.id
                    )
                FROM cte_systems s
            ),
            '[]'::jsonb
        ),
        'containers',
        COALESCE(
            (
                SELECT jsonb_agg(
                        i
                        ORDER BY i.id
                    )
                FROM cte_containers i
            ),
            '[]'::jsonb
        ),
        'interfaces',
        COALESCE(
            (
                SELECT jsonb_agg(
                        i
                        ORDER BY i.id
                    )
                FROM cte_interfaces i
            ),
            '[]'::jsonb
        ),
        'operations',
        COALESCE(
            (
                SELECT jsonb_agg(
                        o
                        ORDER BY o.uid
                    )
                FROM cte_operations o
            ),
            '[]'::jsonb
        )
    )::text
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
		// DEBUG: log datasource metadata
		try {
			var ds = sparxJdbcTemplate.getDataSource();
			if (ds != null) {
				try (var conn = ds.getConnection()) {
					var meta = conn.getMetaData();
					log.warn("SPARX_DATASOURCE_DIAG: driverName={}, driverVersion={}, url={}, productName={}",
							meta.getDriverName(), meta.getDriverVersion(),
							meta.getURL(), meta.getDatabaseProductName());
				}
			} else {
				log.warn("SPARX_DATASOURCE_DIAG: datasource is null");
			}
		} catch (Exception e) {
			log.warn("SPARX_DATASOURCE_DIAG: failed to obtain metadata", e);
		}
		return sparxJdbcTemplate.query(FIND_ALL_SCENARIOS, (rs, rowNum) -> {
			E2EScenarioMeta meta = new E2EScenarioMeta();
			meta.setUid(rs.getString("uid"));
			meta.setName(rs.getString("name"));
			meta.setVersion(rs.getString("version"));
			meta.setNotes(rs.getString("notes"));
			return meta;
		});
	}

	/**
	 * Full raw export of one e2e scenario
	 * (entrance_diagram_uid/diagrams/objects/systems/containers/interfaces/operations),
	 * straight from Sparx EA — no collapsing. Returns the jsonb payload as text
	 * (Postgres builds the JSON
	 * server-side); {@code null} if the datasource isn't configured.
	 */
	public String fetchScenarioRaw(String entranceDiagramUid) {
		if (sparxJdbcTemplate == null) {
			log.warn("Sparx datasource not configured (staging.sparx.datasource.url not set) — cannot fetch uid={}",
					entranceDiagramUid);
			return null;
		}
		return sparxJdbcTemplate.queryForObject(FETCH_SCENARIO_RAW, String.class, entranceDiagramUid);
	}
}
