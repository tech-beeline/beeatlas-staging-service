/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.beeline.staging.dto.scan.ChildStat;
import ru.beeline.staging.dto.scan.ScanRun;
import ru.beeline.staging.dto.scan.ScanRunDetails;
import ru.beeline.staging.dto.scan.ScanRunPage;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ScanRunRepository {

    private static final String SCANS_WHERE_CLAUSE = """
            WHERE r.parent_run_id IS NULL
                AND (?::text IS NULL OR LOWER(r.artifact_type) = LOWER(?))
                AND (?::text IS NULL OR LOWER(s.name) = LOWER(?))
                AND (?::text IS NULL OR LOWER(r.status) = LOWER(?))
                AND (?::timestamp IS NULL OR r.started_at >= ?::timestamp)
                AND (?::timestamp IS NULL OR r.started_at <= ?::timestamp)
            """;

    private static final String COUNT_SCANS = """
            SELECT count(*)
            FROM staging.pipeline_runs r
            JOIN staging.configurations c ON c.id=r.configuration_id
            JOIN staging.source_systems s ON s.id=c.source_system_id
            """ + SCANS_WHERE_CLAUSE;

    private static final String SELECT_SCAN_RUNS = """
            WITH cte_scans AS (
                SELECT
                    r.id,
                    c.code,
                    r.artifact_type,
                    r.status,
                    s.name as source_name,
                    started_at,
                    completed_at
                FROM staging.pipeline_runs r
                JOIN staging.configurations c ON c.id=r.configuration_id
                JOIN staging.source_systems s ON s.id=c.source_system_id
            """ + SCANS_WHERE_CLAUSE + """
            ), cte_childs AS (
                SELECT
                    s.id, r.status, count(*) as cnt
                FROM cte_scans s
                JOIN staging.pipeline_runs r ON r.parent_run_id=s.id
                GROUP BY s.id, r.status
            )
            SELECT
                s.*,
                (
                    SELECT jsonb_agg(
                        jsonb_build_object(
                            'status', c.status,
                            'count', c.cnt
                        ))
                    FROM cte_childs c
                    WHERE c.id=s.id) as child_stats
            FROM cte_scans s
            ORDER BY started_at DESC, s.id DESC
            LIMIT ?
            OFFSET ?
            """;

    private static final String SELECT_SCAN_DETAILS = """
            SELECT
                r.id,
                c.code,
                r.artifact_type,
                r.status,
                s.name as source_name,
                r.started_at,
                r.completed_at,
                (
                    SELECT jsonb_agg(
                        jsonb_build_object(
                            'status', child.status,
                            'count', cnt
                        )
                    )
                    FROM (
                        SELECT status, count(*) as cnt
                        FROM staging.pipeline_runs
                        WHERE parent_run_id = r.id
                        GROUP BY status
                    ) child
                ) as child_stats
            FROM staging.pipeline_runs r
            JOIN staging.configurations c ON c.id = r.configuration_id
            JOIN staging.source_systems s ON s.id = c.source_system_id
            WHERE r.id = ?
              AND r.parent_run_id IS NULL
            """;

    private final JdbcTemplate stagingJdbcTemplate;
    private final ObjectMapper objectMapper;

    public ScanRunRepository(@Qualifier("stagingJdbcTemplate") JdbcTemplate stagingJdbcTemplate,
                              ObjectMapper objectMapper) {
        this.stagingJdbcTemplate = stagingJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ScanRunPage findScans(String artifactType, String sourceName, String status,
                                  LocalDateTime dateFrom, LocalDateTime dateTo,
                                  int limit, int offset) {
        Timestamp from = dateFrom != null ? Timestamp.valueOf(dateFrom) : null;
        Timestamp to = dateTo != null ? Timestamp.valueOf(dateTo) : null;

        Long totalCount = stagingJdbcTemplate.queryForObject(COUNT_SCANS, Long.class,
                artifactType, artifactType,
                sourceName, sourceName,
                status, status,
                from, from,
                to, to);

        List<ScanRun> results = stagingJdbcTemplate.query(SELECT_SCAN_RUNS, (rs, rowNum) -> new ScanRun(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("artifact_type"),
                rs.getString("status"),
                rs.getString("source_name"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
                parseChildStats(rs.getString("child_stats"))
        ), artifactType, artifactType,
                sourceName, sourceName,
                status, status,
                from, from,
                to, to,
                limit, offset);

        return new ScanRunPage(totalCount != null ? totalCount : 0, results);
    }

    /**
     * Найти детали одного скан-запуска по ID.
     * Возвращает пустой Optional если scanId не найден или если это не скан (parent_run_id IS NOT NULL).
     *
     * Ported from documentation/staging-service/queries/select-scan-details.sql
     */
    public Optional<ScanRunDetails> findScanDetails(Long scanId) {
        List<ScanRunDetails> results = stagingJdbcTemplate.query(SELECT_SCAN_DETAILS, (rs, rowNum) -> new ScanRunDetails(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("artifact_type"),
                rs.getString("status"),
                rs.getString("source_name"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
                parseChildStats(rs.getString("child_stats"))
        ), scanId);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private List<ChildStat> parseChildStats(String childStatsJson) {
        if (childStatsJson == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(childStatsJson, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ChildStat.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse child_stats JSON: " + childStatsJson, e);
        }
    }
}
