package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.TransformResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ported from documentation/staging-service/source-artefacts/metric-queries/metric-queries-transform-spec.md
 * v2.1 — keep in sync with that spec (§4/§6 in particular). Extracts targets from panels[0] only
 * (Rule 1), parameterizes Grafana variables into the CM-07 placeholder set, and deduplicates by
 * (metric_code, datasource.type) (CM-03/CM-04).
 *
 * <p>Two documented rules are implemented as best-effort approximations, flagged inline, because the
 * spec doesn't give a concrete worked example to pin the exact behavior:
 * <ul>
 *   <li>${sparxMetadata.name} → {{artifact_name}} (§4.4): the table's variable syntax doesn't match
 *       Grafana's actual $VAR/${VAR} forms, so this is done as a literal substring replacement of
 *       the artifact's name wherever it appears verbatim in expr/query.</li>
 *   <li>{{time_range}} (CM-07-03, OpenSearch only): no field/placement example is given, so it is
 *       not emitted.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueriesTransformer implements ArtifactTransformer {

    public static final String MODULE_CODE = "metric-queries-transformer";

    private static final Set<String> KNOWN_DATASOURCE_TYPES = Set.of("prometheus", "opensearch", "grafana");

    private static final Map<String, String> REFID_TO_METRIC_CODE = Map.of(
            "B", "total_rate",
            "C", "error_rate",
            "A75", "latency_percentile",
            "A95", "latency_percentile",
            "E4xx", "client_error_rate");

    // §4.4 variable patterns
    private static final Pattern MODIFIER_VAR = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*):([^}]+)\\}");
    private static final Pattern BRACED_VAR   = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)\\}");
    private static final Pattern BARE_VAR     = Pattern.compile("(?<![/$])\\$([A-Z_][A-Z0-9_]*)");

    // §4.5 Prometheus range vector, e.g. [5m]
    private static final Pattern RANGE_VECTOR = Pattern.compile("\\[(\\d+[smhdwy])]");

    // §4.6 histogram_quantile(0.75, ...) / quantile_over_time(0.75, ...)
    private static final Pattern PERCENTILE_FUNC =
            Pattern.compile("(histogram_quantile|quantile_over_time)\\(\\s*(0?\\.\\d+|\\d+(?:\\.\\d+)?)\\s*,");

    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Extracts panels[0] targets into a parameterized MetricQueriesObjectPublish snapshot"; }

    @Override
    public TransformResult transform(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);
        JsonNode sparxMetadata = root.path("sparxMetadata");
        JsonNode dashboard = root.path("grafanaDashboard").path("dashboard");
        JsonNode resolvedDatasources = root.path("resolvedDatasources");

        String entityType = sparxMetadata.path("entity_type").asText(null);
        String artifactName = sparxMetadata.path("name").asText(null);

        List<ArtifactNotice> notices = new ArrayList<>();
        List<MetricTemplate> metricTemplates = new ArrayList<>();

        JsonNode panels = dashboard.path("panels");
        if (!panels.isArray() || panels.isEmpty()) {
            notices.add(warning("metric_queries.transform.warning.empty_panels", "panels is missing or empty",
                    artifactUid, "/grafanaDashboard/dashboard/panels"));
        } else {
            metricTemplates.addAll(extractFromFirstPanel(panels.get(0), resolvedDatasources, artifactName, artifactUid, notices));
        }

        if (metricTemplates.isEmpty()) {
            notices.add(warning("metric_queries.transform.warning.empty_metrics", "metricTemplates is empty after transformation",
                    artifactUid, "/grafanaDashboard/dashboard/panels/0/targets"));
        }

        MetricQueriesObjectPublish snapshot = new MetricQueriesObjectPublish(
                "1.0", Instant.now().toString(), entityType, artifactUid, metricTemplates);

        log.info("metric-queries transform for uid={}: {} metric template(s), {} notice(s)",
                artifactUid, metricTemplates.size(), notices.size());
        return TransformResult.of(snapshot, notices);
    }

    private List<MetricTemplate> extractFromFirstPanel(JsonNode panel0, JsonNode resolvedDatasources,
                                                         String artifactName, String artifactUid,
                                                         List<ArtifactNotice> notices) {
        String panelDatasourceType = resolvePanelDatasourceType(panel0, artifactUid, notices);
        JsonNode targets = panel0.path("targets");
        if (!targets.isArray()) {
            return List.of();
        }

        Map<String, MetricTemplate> byKey = new LinkedHashMap<>();
        Map<String, String> keptComparableTextByKey = new HashMap<>();

        for (int i = 0; i < targets.size(); i++) {
            JsonNode target = targets.get(i);
            String refId = target.path("refId").asText(null);
            String metricCode = REFID_TO_METRIC_CODE.get(refId);
            if (metricCode == null) {
                notices.add(warning("metric_queries.transform.warning.unknown_refId", "refId=" + refId,
                        artifactUid, targetContext(i)));
                continue;
            }

            String effectiveType = resolveTargetDatasourceType(target, panelDatasourceType, refId, i, artifactUid, notices);
            ObjectNode node = (ObjectNode) target.deepCopy();
            applyJunkFieldRemoval(node, effectiveType);

            String datasourceUid = resolveDatasourceUid(resolvedDatasources, refId, i, artifactUid, notices);
            ObjectNode datasourceNode = objectMapper.createObjectNode();
            if (effectiveType != null) datasourceNode.put("type", effectiveType);
            datasourceNode.put("uid", datasourceUid);
            node.set("datasource", datasourceNode);

            Double percentile;
            String comparableText;
            if ("prometheus".equals(effectiveType)) {
                percentile = applyPrometheusRules(node, datasourceUid, artifactName, refId, i, artifactUid, notices);
                comparableText = node.path("expr").asText(null);
            } else if ("opensearch".equals(effectiveType)) {
                percentile = applyOpenSearchRules(node, datasourceUid, artifactName, refId, i, artifactUid, notices);
                comparableText = node.path("query").asText(null);
            } else {
                percentile = null;
                comparableText = null;
            }
            if (percentile != null) {
                node.put("percentile", percentile);
            }

            String key = metricCode + "|" + effectiveType;
            if (byKey.containsKey(key)) {
                notices.add(warning("metric_queries.transform.warning.duplicate_refId",
                        "refId=" + refId + " duplicates existing (metric_code=" + metricCode + ", datasource.type=" + effectiveType + ")",
                        artifactUid, targetContext(i)));
                if ("latency_percentile".equals(metricCode)) {
                    String keptText = keptComparableTextByKey.get(key);
                    if (keptText != null && !keptText.equals(comparableText)) {
                        notices.add(warning("metric_queries.transform.warning.latency_percentile_mismatch",
                                "refId=" + refId + " differs from the kept candidate beyond the percentile number",
                                artifactUid, targetContext(i)));
                    }
                }
                continue;
            }
            byKey.put(key, new MetricTemplate(metricCode, node));
            keptComparableTextByKey.put(key, comparableText);
        }

        return new ArrayList<>(byKey.values());
    }

    /** Rule 2 + CM-05-02: elasticsearch normalizes to opensearch; 'grafana' (annotations) is ignored. */
    private String resolvePanelDatasourceType(JsonNode panel0, String artifactUid, List<ArtifactNotice> notices) {
        String rawType = panel0.path("datasource").path("type").asText(null);
        if (rawType == null) {
            return null;
        }
        String normalized = "elasticsearch".equals(rawType) ? "opensearch" : rawType;
        if (!KNOWN_DATASOURCE_TYPES.contains(normalized)) {
            notices.add(warning("metric_queries.transform.warning.unknown_datasource_type",
                    "panels[0].datasource.type=" + rawType, artifactUid, "/grafanaDashboard/dashboard/panels/0/datasource"));
            return null;
        }
        return "grafana".equals(normalized) ? null : normalized;
    }

    /** CM-05-01: on conflict between panel-level type and target structure, structure wins. */
    private String resolveTargetDatasourceType(JsonNode target, String panelType, String refId, int index,
                                                String artifactUid, List<ArtifactNotice> notices) {
        boolean hasExpr = target.path("expr").isTextual() && !target.path("expr").asText().isBlank();
        boolean hasOpenSearchShape = (target.path("bucketAggs").isArray() && !target.path("bucketAggs").isEmpty())
                || (target.path("query").isTextual() && !target.path("query").asText().isBlank());

        String structural = null;
        if (hasExpr && !hasOpenSearchShape) structural = "prometheus";
        else if (hasOpenSearchShape && !hasExpr) structural = "opensearch";

        if (structural != null && panelType != null && !structural.equals(panelType)) {
            notices.add(warning("metric_queries.transform.warning.datasource_type_conflict",
                    "refId=" + refId + ": target structure implies " + structural + " but panel datasource.type=" + panelType,
                    artifactUid, targetContext(index)));
            return structural;
        }
        if (structural != null) return structural;
        if (panelType != null) return panelType;

        notices.add(warning("metric_queries.transform.warning.unknown_datasource_type",
                "refId=" + refId + ": cannot determine datasource type", artifactUid, targetContext(index)));
        return null;
    }

    /** Rule 3: junk fields, per panel-level (structure-resolved) datasource type. */
    private void applyJunkFieldRemoval(ObjectNode node, String effectiveType) {
        if ("prometheus".equals(effectiveType)) {
            node.remove(List.of("query", "bucketAggs", "metrics", "timeField"));
        } else if ("opensearch".equals(effectiveType)) {
            node.remove(List.of("expr", "legendFormat"));
        }
    }

    /** CM-05-03: resolved UID for ${DATASOURCE}; falls back to the literal placeholder + warning. */
    private String resolveDatasourceUid(JsonNode resolvedDatasources, String refId, int index, String artifactUid,
                                         List<ArtifactNotice> notices) {
        String uid = resolvedDatasources.path("${DATASOURCE}").path("uid").asText(null);
        if (uid == null) {
            notices.add(warning("metric_queries.transform.warning.datasource_not_resolved",
                    "refId=" + refId + ": resolvedDatasources[\"${DATASOURCE}\"].uid missing, keeping literal ${DATASOURCE}",
                    artifactUid, targetContext(index)));
            return "${DATASOURCE}";
        }
        return uid;
    }

    private Double applyPrometheusRules(ObjectNode node, String datasourceUid, String artifactName, String refId,
                                         int index, String artifactUid, List<ArtifactNotice> notices) {
        if (!node.has("expr") || !node.get("expr").isTextual()) {
            return null;
        }
        String expr = parameterizeVariables(node.get("expr").asText(), datasourceUid, artifactName);

        Matcher rangeMatcher = RANGE_VECTOR.matcher(expr);
        if (rangeMatcher.find()) {
            expr = rangeMatcher.replaceAll("[{{aggregation_period}}]");
        } else {
            notices.add(warning("metric_queries.transform.warning.missing_aggregation_period",
                    "refId=" + refId + ": no [N<unit>] range vector found", artifactUid, targetContext(index)));
        }

        Double percentile = null;
        Matcher percMatcher = PERCENTILE_FUNC.matcher(expr);
        if (percMatcher.find()) {
            percentile = toPercent(percMatcher.group(2));
            expr = expr.substring(0, percMatcher.start(2)) + "{{percentile}}" + expr.substring(percMatcher.end(2));
        }

        node.put("expr", expr);
        return percentile;
    }

    private Double applyOpenSearchRules(ObjectNode node, String datasourceUid, String artifactName, String refId,
                                         int index, String artifactUid, List<ArtifactNotice> notices) {
        if (node.has("query") && node.get("query").isTextual()) {
            node.put("query", parameterizeVariables(node.get("query").asText(), datasourceUid, artifactName));
        }

        boolean foundDateHistogram = false;
        if (node.has("bucketAggs") && node.get("bucketAggs").isArray()) {
            for (JsonNode agg : node.get("bucketAggs")) {
                if (!"date_histogram".equals(agg.path("type").asText(null))) continue;
                foundDateHistogram = true;
                JsonNode settings = agg.path("settings");
                if (settings.isObject() && settings.has("interval")) {
                    String interval = settings.get("interval").asText();
                    if (!"auto".equals(interval)) {
                        ((ObjectNode) settings).put("interval", "{{aggregation_period}}");
                    }
                }
                break;
            }
        }
        if (!foundDateHistogram) {
            notices.add(warning("metric_queries.transform.warning.missing_aggregation_period",
                    "refId=" + refId + ": no date_histogram bucketAgg found", artifactUid, targetContext(index)));
        }

        Double percentile = null;
        if (node.has("metrics") && node.get("metrics").isArray()) {
            for (JsonNode metric : node.get("metrics")) {
                if (!"percentiles".equals(metric.path("type").asText(null))) continue;
                JsonNode settings = metric.path("settings");
                if (settings.isObject() && settings.has("percents")
                        && settings.get("percents").isArray() && !settings.get("percents").isEmpty()) {
                    percentile = toPercent(settings.get("percents").get(0).asText());
                    ArrayNode newPercents = objectMapper.createArrayNode();
                    newPercents.add("{{percentile}}");
                    ((ObjectNode) settings).set("percents", newPercents);
                }
                break;
            }
        }
        return percentile;
    }

    /** §4.4: $NAME / ${NAME} / ${NAME:modifier} → CM-07 placeholders; ${DATASOURCE} → resolved UID. */
    private String parameterizeVariables(String text, String datasourceUid, String artifactName) {
        if (text == null) return null;
        String result = replaceMatches(text, MODIFIER_VAR, datasourceUid);
        result = replaceMatches(result, BRACED_VAR, datasourceUid);
        result = replaceMatches(result, BARE_VAR, datasourceUid);

        if (artifactName != null && !artifactName.isBlank() && result.contains(artifactName)) {
            result = result.replace(artifactName, "{{artifact_name}}");
        }
        return result;
    }

    private String replaceMatches(String text, Pattern pattern, String datasourceUid) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = mapVariableName(matcher.group(1), datasourceUid, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String mapVariableName(String name, String datasourceUid, String originalMatch) {
        return switch (name) {
            case "METHOD" -> "{{method}}";
            case "URI" -> "{{uri}}";
            case "REGEX_URI" -> "{{uri_regex}}";
            case "DATASOURCE" -> datasourceUid;
            default -> originalMatch; // CM-07-04: leave unrecognized variables as-is
        };
    }

    /** 0.75 → 75.0, "75" → 75.0 (Prometheus fraction vs OpenSearch whole-number-string). */
    private double toPercent(String raw) {
        double value = Double.parseDouble(raw);
        return value <= 1.0 ? Math.round(value * 100.0) : value;
    }

    private String targetContext(int index) {
        return "/grafanaDashboard/dashboard/panels/0/targets/" + index;
    }

    // ArtifactNoticeEntity only persists code/level/category (via notice_type) + details + context —
    // message() is never written by ArtifactNoticeService, so the human-readable text has to live
    // in details (matches E2ESequenceValidator's convention).
    private ArtifactNotice warning(String code, String message, String entityUid, String context) {
        return new ArtifactNotice(null, null, code, "warning", "transform",
                null, null, entityUid, null, message, toDetailsJson(message), context, null);
    }

    private String toDetailsJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("message", message));
        } catch (Exception e) {
            return "{}";
        }
    }
}
