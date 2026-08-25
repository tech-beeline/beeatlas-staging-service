package ru.beeline.staging.grafana;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import ru.beeline.staging.grafana.dto.GrafanaDatasource;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ported from documentation/staging-service/source-artefacts/metric-queries/metric-queries-adapter-spec.md
 * §5 — keep in sync with that spec. getDashboardByUID / getDatasources, manual retry (adapter-spec §5.3).
 */
@Slf4j
@Repository
public class GrafanaClient {

    private static final Pattern DASHBOARD_UID_PATTERN = Pattern.compile("/d(?:-solo)?/([^/]+)");

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String token;
    private final int retryCount;
    private final long retryDelayMs;

    public GrafanaClient(RestTemplate restTemplate,
                          @Value("${staging.grafana.url}") String baseUrl,
                          @Value("${staging.grafana.token}") String token,
                          @Value("${integration.grafana.retry-count:3}") int retryCount,
                          @Value("${integration.grafana.retry-delay-ms:1000}") long retryDelayMs) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.retryCount = retryCount;
        this.retryDelayMs = retryDelayMs;
    }

    /** Extracts the dashboard UID from an api-metric-template URL (/d/{uid}/... or /d-solo/{uid}/...). */
    public static String extractDashboardUid(String apiMetricTemplateUrl) {
        Matcher matcher = DASHBOARD_UID_PATTERN.matcher(apiMetricTemplateUrl == null ? "" : apiMetricTemplateUrl);
        if (!matcher.find()) {
            throw new IllegalStateException("Cannot extract Grafana dashboard uid from URL: " + apiMetricTemplateUrl);
        }
        return matcher.group(1);
    }

    /** GET /api/dashboards/uid/{uid} — full dashboard JSON, returned as-is (ADR-008 Full Export). */
    public String getDashboardByUID(String uid) {
        String url = baseUrl + "/api/dashboards/uid/" + uid;
        return callWithRetry(url, String.class);
    }

    /** GET /api/datasources. */
    public List<GrafanaDatasource> getDatasources() {
        String url = baseUrl + "/api/datasources";
        GrafanaDatasource[] result = callWithRetry(url, GrafanaDatasource[].class);
        return result == null ? List.of() : List.of(result);
    }

    private <T> T callWithRetry(String url, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        HttpEntity<Void> request = new HttpEntity<>(headers);

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.GET, request, responseType);
                return response.getBody();
            } catch (HttpClientErrorException e) {
                throw new IllegalStateException("Grafana rejected request: url=" + url
                        + " status=" + e.getStatusCode() + " body=" + e.getResponseBodyAsString(), e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                if (attempt > retryCount) {
                    throw new IllegalStateException("Grafana unreachable after " + retryCount + " retries: url=" + url, e);
                }
                log.warn("Grafana call failed for url={} (attempt {}/{}), retrying in {}ms: {}",
                        url, attempt, retryCount, retryDelayMs, e.getMessage());
                sleep(retryDelayMs);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying Grafana call", e);
        }
    }
}
