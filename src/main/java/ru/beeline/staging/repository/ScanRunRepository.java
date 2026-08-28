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
                -- Not parent_run_id: a rediscovered artifact reuses its earlier run (dedup fix),
                -- so that run's parent_run_id still points at whichever scan first created it, not
                -- this one. source_artifacts.last_seen_scan_run_id/last_run_id are updated on every
                -- find (new or reused), so they reflect "what this scan currently sees", not "what
                -- this scan happened to create".
                SELECT
                    s.id, r.status, count(*) as cnt
                FROM cte_scans s
                JOIN staging.source_artifacts sa ON sa.last_seen_scan_run_id = s.id
                JOIN staging.pipeline_runs r ON r.id = sa.last_run_id
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
                    -- Same reasoning as cte_childs in SELECT_SCAN_RUNS above: count what this scan
                    -- currently sees via source_artifacts, not what it happened to create.
                    SELECT jsonb_agg(
                        jsonb_build_object(
                            'status', child.status,
                            'count', cnt
                        )
                    )
                    FROM (
                        SELECT pr.status, count(*) as cnt
                        FROM staging.source_artifacts sa
                        JOIN staging.pipeline_runs pr ON pr.id = sa.last_run_id
                        WHERE sa.last_seen_scan_run_id = r.id
                        GROUP BY pr.status
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

        List<ScanRun> results = stagingJdbcTemplate.query(SELECT_SCAN_RUNS, (rs, rowNum) -> {
            List<ChildStat> childStats = parseChildStats(rs.getString("child_stats"));
            String rowStatus = rs.getString("status");
            return new ScanRun(
                    rs.getLong("id"),
                    rs.getString("code"),
                    rs.getString("artifact_type"),
                    rowStatus,
                    displayStatus(rowStatus, childStats),
                    rs.getString("source_name"),
                    rs.getTimestamp("started_at").toLocalDateTime(),
                    rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
                    childStats
            );
        }, artifactType, artifactType,
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
        List<ScanRunDetails> results = stagingJdbcTemplate.query(SELECT_SCAN_DETAILS, (rs, rowNum) -> {
            List<ChildStat> childStats = parseChildStats(rs.getString("child_stats"));
            String status = rs.getString("status");
            return new ScanRunDetails(
                    rs.getLong("id"),
                    rs.getString("code"),
                    rs.getString("artifact_type"),
                    status,
                    displayStatus(status, childStats),
                    rs.getString("source_name"),
                    rs.getTimestamp("started_at").toLocalDateTime(),
                    rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
                    childStats
            );
        }, scanId);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // The raw `status` column on a scan row only reflects the discovery/fan-out stage (see
    // finishScanWithChildren) — it flips to "completed" as soon as children are created/reused,
    // regardless of whether those children have finished. Left as-is, the UI showed "Завершён"
    // for a scan whose children were still processing (or, before the executeStageWithMetrics
    // safety net, silently stuck forever) — indistinguishable from a scan where everything is
    // actually done. displayStatus folds child completion in so the two cases render differently.
    private String displayStatus(String status, List<ChildStat> childStats) {
        if (!"completed".equals(status)) {
            return status;
        }
        long total = childStats.stream().mapToLong(ChildStat::count).sum();
        long terminal = childStats.stream()
                .filter(cs -> "completed".equals(cs.status()) || "failed".equals(cs.status()))
                .mapToLong(ChildStat::count).sum();
        return (total == 0 || terminal == total) ? "completed" : "in_progress";
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
