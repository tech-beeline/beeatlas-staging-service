package ru.beeline.staging.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.pipeline.transformer.MetricQueriesObjectPublish;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.util.List;
import java.util.Map;

/**
 * Ported from documentation/staging-service/source-artefacts/metric-queries/metric-queries-save-spec.md
 * §6 (ADR-012 HTTP push) — keep in sync with that spec. POST /api/v1/metric-query-templates
 * (UPSERT by uid+entity_type). Structurally mirrors E2eProductsClient (manual retry loop), but —
 * unlike E2eProductsPublisher, which rethrows on failure and fails the whole save stage — a publish
 * failure here is a warning/error notice only; the pipeline run stays 'completed' (save-spec §7 —
 * the staging snapshot is the source of truth, republished on the next successful run).
 */
@Slf4j
@Repository
public class DashboardServicePublishClient {

    private final RestTemplate restTemplate;
    private final ArtifactNoticeService noticeService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final int retryCount;
    private final long retryDelayMs;

    public DashboardServicePublishClient(RestTemplate restTemplate,
                                          ArtifactNoticeService noticeService,
                                          ObjectMapper objectMapper,
                                          @Value("${staging.dashboard.base-url}") String baseUrl,
                                          @Value("${integration.metric-queries-publish.retry-count:3}") int retryCount,
                                          @Value("${integration.metric-queries-publish.retry-delay-ms:1000}") long retryDelayMs) {
        this.restTemplate = restTemplate;
        this.noticeService = noticeService;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.retryCount = retryCount;
        this.retryDelayMs = retryDelayMs;
    }

    /** @return true if the dashboard-service accepted the snapshot (HTTP 200/201). */
    public boolean publish(MetricQueriesObjectPublish snapshot, Long rawDataRefId) {
        String url = baseUrl + "/api/v1/metric-query-templates";
        String uid = snapshot.uid();

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                restTemplate.postForEntity(url, snapshot, Void.class);
                log.info("Published metric-queries snapshot uid={} to dashboard-service", uid);
                saveNotice("metric_queries.saver.publish.success", "info", rawDataRefId, uid, "HTTP 200/201");
                return true;
            } catch (HttpClientErrorException e) {
                // 4xx (incl. 422 schema validation) — not retried, per save-spec §6.2.
                log.error("dashboard-service rejected metric-queries publish: uid={} url={} status={} body={}",
                        uid, url, e.getStatusCode(), e.getResponseBodyAsString());
                saveNotice("metric_queries.saver.publish.rejected", "error", rawDataRefId, uid,
                        "status=" + e.getStatusCode() + " body=" + e.getResponseBodyAsString());
                return false;
            } catch (HttpServerErrorException e) {
                if (attempt > retryCount) {
                    log.warn("dashboard-service publish failed after {} retries: uid={} url={}", retryCount, uid, url);
                    saveNotice("metric_queries.saver.publish.failed", "warning", rawDataRefId, uid,
                            "exhausted " + retryCount + " retries: " + e.getMessage());
                    return false;
                }
                log.warn("dashboard-service publish failed for uid={} (attempt {}/{}), retrying in {}ms: {}",
                        uid, attempt, retryCount, retryDelayMs, e.getMessage());
                saveNotice("metric_queries.saver.publish.retry", "info", rawDataRefId, uid,
                        "attempt " + attempt + "/" + retryCount + ": " + e.getMessage());
                sleep(retryDelayMs);
            } catch (ResourceAccessException e) {
                if (attempt > retryCount) {
                    log.warn("dashboard-service unreachable after {} retries: uid={} url={}", retryCount, uid, url);
                    saveNotice("metric_queries.saver.publish.unavailable", "warning", rawDataRefId, uid, e.getMessage());
                    return false;
                }
                log.warn("dashboard-service unreachable for uid={} (attempt {}/{}), retrying in {}ms: {}",
                        uid, attempt, retryCount, retryDelayMs, e.getMessage());
                saveNotice("metric_queries.saver.publish.retry", "info", rawDataRefId, uid,
                        "attempt " + attempt + "/" + retryCount + ": " + e.getMessage());
                sleep(retryDelayMs);
            }
        }
    }

    // ArtifactNoticeEntity only persists code/level/category (via notice_type) + details + context —
    // message() is never written, so the human-readable text lives in details (JSON).
    private void saveNotice(String code, String level, Long rawDataRefId, String entityUid, String message) {
        if (rawDataRefId == null) return;
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(Map.of("message", message));
        } catch (Exception e) {
            detailsJson = "{}";
        }
        ArtifactNotice notice = new ArtifactNotice(null, null, code, level, "publish",
                rawDataRefId, "metric_query_template", entityUid, null, message, detailsJson, null, null);
        noticeService.saveNoticeInNewTransaction(rawDataRefId, notice);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying dashboard-service publish", e);
        }
    }
}
