package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.TransformResult;

import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures below are trimmed/re-typed versions of the panels[0] content from
 * documentation/staging-service/source-artefacts/metric-queries/grafana-dashbaord-samples/
 * {prometheus,opensearch}-dashbaord.json (not read from that sibling repo directly — tests
 * shouldn't depend on paths outside this repo).
 */
class MetricQueriesTransformerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetricQueriesTransformer transformer = new MetricQueriesTransformer(objectMapper);

    @Test
    void transformsPrometheusDashboard_parameterizesAndDedupsA75A95() throws Exception {
        String raw = """
            {
              "sparxMetadata": {"entity_type": "product", "name": "beeatlas", "apiMetricTemplateUrl": "https://grafana/d/56_72PcHk/x"},
              "resolvedDatasources": {"${DATASOURCE}": {"uid": "prom-uid-1", "name": "Prometheus-BeeInside", "type": "prometheus"}},
              "grafanaDashboard": {
                "dashboard": {
                  "panels": [
                    {
                      "datasource": {"type": "prometheus", "uid": "${DATASOURCE}"},
                      "targets": [
                        {"refId": "A75", "datasource": {"type": "prometheus", "uid": "${DATASOURCE}"},
                         "expr": "histogram_quantile(0.75, sum by(le)(increase(http_server_requests_seconds_bucket{ uri=~\\"(?i)$URI\\", method=\\"$METHOD\\"}[5m]))) * 1000"},
                        {"refId": "A95", "datasource": {"type": "prometheus", "uid": "${DATASOURCE}"},
                         "expr": "histogram_quantile(0.95, sum by(le)(increase(http_server_requests_seconds_bucket{ uri=~\\"(?i)$URI\\", method=\\"$METHOD\\"}[5m]))) * 1000"},
                        {"refId": "B", "datasource": {"type": "prometheus", "uid": "${DATASOURCE}"},
                         "expr": "sum (delta (http_server_requests_seconds_count{uri=~\\"(?i)$URI\\", method=\\"$METHOD\\"}[5m]))"},
                        {"refId": "C", "datasource": {"type": "prometheus", "uid": "${DATASOURCE}"},
                         "expr": "sum (delta (http_server_requests_seconds_count{uri=~\\"(?i)$URI\\", method=\\"$METHOD\\", status=~\\"5..\\"}[5m]))"},
                        {"refId": "E4xx", "datasource": {"type": "prometheus", "uid": "${DATASOURCE}"},
                         "expr": "sum (delta (http_server_requests_seconds_count{uri=~\\"(?i)$URI\\", method=\\"$METHOD\\", status=~\\"4..\\"}[5m]))"}
                      ]
                    }
                  ]
                }
              }
            }
            """;

        TransformResult result = transformer.transform("beeatlas", raw);
        MetricQueriesObjectPublish snapshot = (MetricQueriesObjectPublish) result.snapshot();

        assertThat(snapshot.entityType()).isEqualTo("product");
        assertThat(snapshot.uid()).isEqualTo("beeatlas");
        assertThat(snapshot.metricTemplates()).hasSize(4);
        assertThat(snapshot.metricTemplates()).extracting(MetricTemplate::metricCode)
                .containsExactlyInAnyOrder("total_rate", "error_rate", "latency_percentile", "client_error_rate");

        MetricTemplate latency = findByCode(snapshot, "latency_percentile");
        JsonNode latencyTemplate = latency.template();
        assertThat(latencyTemplate.path("refId").asText()).isEqualTo("A75");
        assertThat(latencyTemplate.path("percentile").asDouble()).isEqualTo(75.0);
        assertThat(latencyTemplate.path("datasource").path("type").asText()).isEqualTo("prometheus");
        assertThat(latencyTemplate.path("datasource").path("uid").asText()).isEqualTo("prom-uid-1");
        String latencyExpr = latencyTemplate.path("expr").asText();
        assertThat(latencyExpr)
                .contains("{{percentile}}")
                .contains("{{aggregation_period}}")
                .contains("{{uri}}")
                .contains("{{method}}")
                .doesNotContain("$URI").doesNotContain("$METHOD").doesNotContain("0.75").doesNotContain("[5m]");

        MetricTemplate totalRate = findByCode(snapshot, "total_rate");
        assertThat(totalRate.template().path("expr").asText())
                .isEqualTo("sum (delta (http_server_requests_seconds_count{uri=~\"(?i){{uri}}\", method=\"{{method}}\"}[{{aggregation_period}}]))");

        // A95 deduped against A75 (same metric_code+datasource.type, identical skeleton after
        // percentile substitution) — duplicate_refId only, no latency_percentile_mismatch.
        assertThat(result.notices()).extracting(ArtifactNotice::code)
                .contains("metric_queries.transform.warning.duplicate_refId")
                .doesNotContain("metric_queries.transform.warning.latency_percentile_mismatch");
    }

    @Test
    void transformsOpenSearchDashboard_bucketAggsAndPercentilesMetrics() throws Exception {
        String raw = """
            {
              "sparxMetadata": {"entity_type": "object", "name": "grafana-source-1", "apiMetricTemplateUrl": "https://grafana/d/hwzG1EcNz/x"},
              "resolvedDatasources": {"${DATASOURCE}": {"uid": "os-uid-1", "name": "logstash-ingress", "type": "elasticsearch"}},
              "grafanaDashboard": {
                "dashboard": {
                  "panels": [
                    {
                      "datasource": {"type": "elasticsearch", "uid": "${DATASOURCE}"},
                      "targets": [
                        {"refId": "A75", "query": "json.request_uri.keyword: /${REGEX_URI:raw}/ AND json.request_method: \\"$METHOD\\"",
                         "bucketAggs": [{"type": "date_histogram", "settings": {"interval": "5m"}}],
                         "metrics": [{"type": "percentiles", "settings": {"percents": ["75"]}}]},
                        {"refId": "A95", "query": "json.request_uri.keyword: /${REGEX_URI:raw}/ AND json.request_method: \\"$METHOD\\"",
                         "bucketAggs": [{"type": "date_histogram", "settings": {"interval": "5m"}}],
                         "metrics": [{"type": "percentiles", "settings": {"percents": ["95"]}}]},
                        {"refId": "B", "query": "json.request_uri.keyword: /${REGEX_URI:raw}/ AND json.request_method: \\"$METHOD\\"",
                         "bucketAggs": [{"type": "date_histogram", "settings": {"interval": "5m"}}],
                         "metrics": [{"type": "count"}]},
                        {"refId": "C", "query": "json.request_uri.keyword: /${REGEX_URI:raw}/ AND json.status: [500 TO 599]",
                         "bucketAggs": [{"type": "date_histogram", "settings": {"interval": "5m"}}],
                         "metrics": [{"type": "count"}]},
                        {"refId": "E4xx", "query": "json.request_uri.keyword: /${REGEX_URI:raw}/ AND json.status: [400 TO 499]",
                         "bucketAggs": [{"type": "date_histogram", "settings": {"interval": "auto"}}],
                         "metrics": [{"type": "count"}]}
                      ]
                    }
                  ]
                }
              }
            }
            """;

        TransformResult result = transformer.transform("grafana-source-1", raw);
        MetricQueriesObjectPublish snapshot = (MetricQueriesObjectPublish) result.snapshot();

        assertThat(snapshot.metricTemplates()).hasSize(4);

        MetricTemplate latency = findByCode(snapshot, "latency_percentile");
        JsonNode latencyTemplate = latency.template();
        assertThat(latencyTemplate.path("datasource").path("type").asText()).isEqualTo("opensearch"); // normalized from elasticsearch
        assertThat(latencyTemplate.path("datasource").path("uid").asText()).isEqualTo("os-uid-1");
        assertThat(latencyTemplate.has("expr")).isFalse();       // junk field stripped for opensearch
        assertThat(latencyTemplate.path("query").asText())
                .contains("{{uri_regex}}").contains("{{method}}")
                .doesNotContain("$METHOD").doesNotContain("REGEX_URI");
        assertThat(latencyTemplate.path("bucketAggs").get(0).path("settings").path("interval").asText())
                .isEqualTo("{{aggregation_period}}");
        assertThat(latencyTemplate.path("metrics").get(0).path("settings").path("percents").get(0).asText())
                .isEqualTo("{{percentile}}");
        assertThat(latencyTemplate.path("percentile").asDouble()).isEqualTo(75.0);

        MetricTemplate clientError = findByCode(snapshot, "client_error_rate");
        // "auto" interval is left untouched per spec (not a fixed period to parameterize)
        assertThat(clientError.template().path("bucketAggs").get(0).path("settings").path("interval").asText())
                .isEqualTo("auto");
    }

    @Test
    void emptyPanels_producesEmptySnapshotWithWarnings() throws Exception {
        String raw = """
            {
              "sparxMetadata": {"entity_type": "container", "name": "x", "apiMetricTemplateUrl": "https://grafana/d/abc/x"},
              "resolvedDatasources": {},
              "grafanaDashboard": {"dashboard": {"panels": []}}
            }
            """;

        TransformResult result = transformer.transform("uid-1", raw);
        MetricQueriesObjectPublish snapshot = (MetricQueriesObjectPublish) result.snapshot();

        assertThat(snapshot.metricTemplates()).isEmpty();
        assertThat(result.notices()).extracting(ArtifactNotice::code)
                .contains("metric_queries.transform.warning.empty_panels",
                        "metric_queries.transform.warning.empty_metrics");
    }

    private MetricTemplate findByCode(MetricQueriesObjectPublish snapshot, String metricCode) {
        Predicate<MetricTemplate> matches = t -> metricCode.equals(t.metricCode());
        Optional<MetricTemplate> found = snapshot.metricTemplates().stream().filter(matches).findFirst();
        assertThat(found).as("metric_code=" + metricCode).isPresent();
        return found.get();
    }
}
