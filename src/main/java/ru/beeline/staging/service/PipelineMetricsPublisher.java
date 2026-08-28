/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// Counters (staging_pipeline_runs_total etc.) only ever go up — they answer "how many completed
// since startup", not "how many are sitting in each status right now". These gauges answer that,
// refreshed periodically rather than per-scrape (Actuator/Prometheus has no easy pre-scrape hook
// into a JdbcTemplate-backed value here).
@Component
@RequiredArgsConstructor
public class PipelineMetricsPublisher {

    private static final String RUNS_BY_TYPE_STATUS = """
            SELECT artifact_type, status, count(*) AS cnt
            FROM staging.pipeline_runs
            WHERE artifact_uid IS NOT NULL
            GROUP BY artifact_type, status
            """;

    private static final String ARTIFACTS_BY_TYPE_STATUS = """
            SELECT t.code AS artifact_type, sa.status, count(*) AS cnt
            FROM staging.source_artifacts sa
            JOIN staging.source_artifact_types sat ON sat.id = sa.source_artifact_type_id
            JOIN staging.data_types t ON t.id = sat.data_type_id
            GROUP BY t.code, sa.status
            """;

    @Qualifier("stagingJdbcTemplate")
    private final JdbcTemplate stagingJdbcTemplate;
    private final MeterRegistry meterRegistry;

    private MultiGauge runsGauge;
    private MultiGauge artifactsGauge;

    @PostConstruct
    void init() {
        runsGauge = MultiGauge.builder("staging_pipeline_runs_current")
                .description("Current pipeline_runs (artifact) row count by artifact_type and status")
                .register(meterRegistry);
        artifactsGauge = MultiGauge.builder("staging_artifacts_current")
                .description("Current known artifact count (source_artifacts) by artifact_type and status")
                .register(meterRegistry);
        refresh();
    }

    @Scheduled(fixedDelayString = "${staging.metrics.gauge-refresh-ms:30000}")
    void refresh() {
        runsGauge.register(rowsOf(RUNS_BY_TYPE_STATUS), true);
        artifactsGauge.register(rowsOf(ARTIFACTS_BY_TYPE_STATUS), true);
    }

    private List<MultiGauge.Row<?>> rowsOf(String sql) {
        return stagingJdbcTemplate.query(sql, (rs, rowNum) -> {
            Long count = rs.getLong("cnt");
            return MultiGauge.Row.of(
                    Tags.of("artifact_type", rs.getString("artifact_type"), "status", rs.getString("status")),
                    count);
        });
    }
}
