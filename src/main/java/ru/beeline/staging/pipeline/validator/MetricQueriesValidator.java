package ru.beeline.staging.pipeline.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.ValidateResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ported from documentation/staging-service/source-artefacts/metric-queries/metric-queries-validator-spec.md
 * v2.1 — keep in sync with that spec. Validates only the grafanaDashboard section of the raw
 * document; targets in panels[0] only (Rule 1, matching MetricQueriesTransformer).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueriesValidator implements ArtifactValidator {

    public static final String MODULE_CODE = "metric-queries-validator";

    private static final Set<String> TARGET_REF_IDS = Set.of("A75", "A95", "B", "C", "E4xx");
    private static final Set<String> KNOWN_DATASOURCE_TYPES = Set.of("prometheus", "elasticsearch", "opensearch", "grafana");

    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Validates the grafanaDashboard section (dashboard JSON structure, panels[0] targets)"; }

    @Override
    public ValidateResult validate(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);
        JsonNode dashboardWrapper = root.path("grafanaDashboard");
        List<ArtifactNotice> notices = new ArrayList<>();

        if (dashboardWrapper.isMissingNode() || dashboardWrapper.isNull() || !dashboardWrapper.path("dashboard").isObject()) {
            notices.add(error("metric_queries.validation.grafana.invalid_dashboard",
                    "grafanaDashboard.dashboard is missing or not an object", artifactUid, "/grafanaDashboard"));
            return ValidateResult.of(notices);
        }

        JsonNode dashboard = dashboardWrapper.path("dashboard");
        JsonNode panels = dashboard.path("panels");
        if (!panels.isArray()) {
            notices.add(warning("metric_queries.validation.grafana.no_panels",
                    "panels is missing or not an array", artifactUid, "/grafanaDashboard/dashboard/panels"));
            return ValidateResult.of(notices);
        }
        if (panels.isEmpty()) {
            notices.add(warning("metric_queries.validation.grafana.no_panels",
                    "panels is empty", artifactUid, "/grafanaDashboard/dashboard/panels"));
            return ValidateResult.of(notices);
        }
        if (panels.size() > 1) {
            notices.add(warning("metric_queries.validation.grafana.extra_panels_ignored",
                    "panels.length=" + panels.size() + " — only panels[0] is processed", artifactUid,
                    "/grafanaDashboard/dashboard/panels"));
        }

        validateFirstPanel(panels.get(0), artifactUid, notices);
        validateTargetsOutsideFirstPanel(panels, artifactUid, notices);

        long errorCount = notices.stream().filter(n -> "error".equals(n.level())).count();
        log.info("metric-queries validation for uid={}: {} notice(s), {} error(s)", artifactUid, notices.size(), errorCount);
        return notices.isEmpty() ? ValidateResult.empty() : ValidateResult.of(notices);
    }

    private void validateFirstPanel(JsonNode panel0, String artifactUid, List<ArtifactNotice> notices) {
        JsonNode datasource = panel0.path("datasource");
        String datasourceType = normalizeDatasourceType(datasource.path("type").asText(null));

        if (!datasource.isMissingNode() && !datasource.isNull()
                && (datasource.path("type").isMissingNode() || !datasource.path("type").isTextual())) {
            notices.add(warning("metric_queries.validation.grafana.invalid_panel_datasource",
                    "panels[0].datasource is present but has no textual 'type'", artifactUid,
                    "/grafanaDashboard/dashboard/panels/0/datasource"));
        }
        if (!datasource.isMissingNode() && !datasource.isNull()
                && datasource.path("type").isTextual() && !KNOWN_DATASOURCE_TYPES.contains(datasource.path("type").asText())) {
            notices.add(warning("metric_queries.validation.grafana.unknown_datasource_type",
                    "panels[0].datasource.type='" + datasource.path("type").asText() + "' is not a known type", artifactUid,
                    "/grafanaDashboard/dashboard/panels/0/datasource"));
        }

        JsonNode targets = panel0.path("targets");
        boolean hasTargetMetric = false;
        if (targets.isArray()) {
            for (int i = 0; i < targets.size(); i++) {
                JsonNode target = targets.get(i);
                String context = "/grafanaDashboard/dashboard/panels/0/targets/" + i;
                String refId = target.path("refId").asText(null);

                if (refId == null || refId.isBlank()) {
                    notices.add(error("metric_queries.validation.grafana.empty_refId",
                            "target[" + i + "].refId is missing or empty", artifactUid, context));
                    continue;
                }
                if (TARGET_REF_IDS.contains(refId)) {
                    hasTargetMetric = true;
                }
                validateQueryExpression(target, datasourceType, refId, artifactUid, context, notices);
            }
        }

        if (!hasTargetMetric) {
            notices.add(warning("metric_queries.validation.grafana.no_target_metrics",
                    "no target with refId in {A75,A95,B,C,E4xx} found in panels[0]", artifactUid,
                    "/grafanaDashboard/dashboard/panels/0/targets"));
        }
    }

    /**
     * Rule 2 + CM-02-03: which field is validated depends on the panel-level datasource type —
     * expr for prometheus, query for opensearch. If the panel's type couldn't be resolved (missing/
     * unknown — already flagged separately), the query-expression check is skipped for its targets.
     */
    private void validateQueryExpression(JsonNode target, String datasourceType, String refId,
                                          String artifactUid, String context, List<ArtifactNotice> notices) {
        if ("prometheus".equals(datasourceType)) {
            String expr = target.path("expr").asText(null);
            if (expr == null || expr.isBlank()) {
                notices.add(error("metric_queries.validation.grafana.empty_query",
                        "target refId=" + refId + ": expr is empty (prometheus)", artifactUid, context));
            }
        } else if ("opensearch".equals(datasourceType)) {
            String query = target.path("query").asText(null);
            if (query == null || query.isBlank()) {
                notices.add(error("metric_queries.validation.grafana.empty_query",
                        "target refId=" + refId + ": query is empty (opensearch)", artifactUid, context));
            }
        }
    }

    private void validateTargetsOutsideFirstPanel(JsonNode panels, String artifactUid, List<ArtifactNotice> notices) {
        for (int p = 1; p < panels.size(); p++) {
            JsonNode targets = panels.get(p).path("targets");
            if (!targets.isArray()) continue;
            for (int i = 0; i < targets.size(); i++) {
                String refId = targets.get(i).path("refId").asText(null);
                if (TARGET_REF_IDS.contains(refId)) {
                    notices.add(warning("metric_queries.validation.grafana.targets_outside_first_panel",
                            "target refId=" + refId + " found in panels[" + p + "], ignored by Transformer", artifactUid,
                            "/grafanaDashboard/dashboard/panels/" + p + "/targets/" + i));
                }
            }
        }
    }

    /** CM-05-02: elasticsearch normalizes to opensearch. */
    private String normalizeDatasourceType(String type) {
        if (type == null) return null;
        return "elasticsearch".equals(type) ? "opensearch" : type;
    }

    // ArtifactNoticeEntity only persists code/level/category (via notice_type) + details + context —
    // message() is never written by ArtifactNoticeService, so the human-readable text has to live
    // in details (matches E2ESequenceValidator's convention).
    private ArtifactNotice error(String code, String message, String entityUid, String context) {
        return new ArtifactNotice(null, null, code, "error", "validation",
                null, null, entityUid, null, message, toDetailsJson(message), context, null);
    }

    private ArtifactNotice warning(String code, String message, String entityUid, String context) {
        return new ArtifactNotice(null, null, code, "warning", "validation",
                null, null, entityUid, null, message, toDetailsJson(message), context, null);
    }

    private String toDetailsJson(String message) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("message", message));
        } catch (Exception e) {
            return "{}";
        }
    }
}
