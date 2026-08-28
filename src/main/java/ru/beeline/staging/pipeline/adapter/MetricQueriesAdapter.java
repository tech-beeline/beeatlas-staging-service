package ru.beeline.staging.pipeline.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.grafana.GrafanaClient;
import ru.beeline.staging.grafana.dto.GrafanaDatasource;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.sparx.MetricQueriesEntityTypeResolver;
import ru.beeline.staging.sparx.SparxMetricQueriesRepository;
import ru.beeline.staging.sparx.dto.MetricQueriesSourceMeta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from documentation/staging-service/source-artefacts/metric-queries/metric-queries-adapter-spec.md
 * — keep in sync with that spec.
 *
 * <p>Note on metadata: the spec describes pre-adapter passing {@code metadata} (entity_type/name/
 * apiMetricTemplateUrl) to the adapter stage. In the current core, {@code AdapterStage.execute()}
 * calls {@code adapter.load(uid, sourceId, null)} — metadata is never actually threaded through.
 * Rather than touch the core stage classes, this adapter re-resolves the object by uid from
 * Sparx EA itself — the same pattern already used
 * by {@link StructurizrSequenceAdapter}, which re-fetches product info from fdm-products instead of
 * trusting passed metadata.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueriesAdapter implements ArtifactAdapter {

    public static final String MODULE_CODE = "metric-queries-adapter";
    public static final String TYPE        = "metric-queries";

    private static final String DATASOURCE_VARIABLE_NAME = "DATASOURCE";
    private static final String DATASOURCE_PLACEHOLDER   = "${DATASOURCE}";

    private final SparxMetricQueriesRepository sparxMetricQueriesRepository;
    private final GrafanaClient                grafanaClient;
    private final RawDataRefRepository         rawDataRefRepository;
    private final ObjectMapper                 objectMapper;

    // In-memory caches, scoped to this singleton bean's JVM lifetime (BR-007-04). The core has no
    // explicit scan-cycle start/end hook to clear these between pre-adapter runs; ${DATASOURCE}
    // resolution and dashboard content change rarely enough that an unbounded, un-expired cache is
    // an acceptable trade-off here rather than adding new core wiring for it.
    private final Map<String, String> dashboardCache = new ConcurrentHashMap<>();
    private final Map<String, List<GrafanaDatasource>> datasourceCache = new ConcurrentHashMap<>();
    private static final String DATASOURCES_CACHE_KEY = "all";

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Loads a Grafana dashboard (Full Export) by the object's api-metric-template URL"; }

    @Override
    public Map<String, Object> load(String artifactUid, String sourceId, Map<String, Object> metadata) throws Exception {
        MetricQueriesSourceMeta source = sparxMetricQueriesRepository.findByUid(artifactUid)
                .orElseThrow(() -> new IllegalStateException(
                        "Object no longer carries api-metric-template in Sparx EA: uid=" + artifactUid));

        Map<String, Object> sparxMetadata = new LinkedHashMap<>();
        sparxMetadata.put("entity_type", MetricQueriesEntityTypeResolver.resolve(source));
        sparxMetadata.put("name", source.getName());
        sparxMetadata.put("apiMetricTemplateUrl", source.getApiMetricTemplate());

        String dashboardUid = GrafanaClient.extractDashboardUid(source.getApiMetricTemplate());
        String dashboardJson = dashboardCache.computeIfAbsent(dashboardUid, uid -> {
            log.info("Fetching Grafana dashboard uid={} for artifactUid={}", uid, artifactUid);
            return grafanaClient.getDashboardByUID(uid);
        });
        JsonNode dashboardRoot = objectMapper.readTree(dashboardJson);

        Map<String, Object> resolvedDatasources = resolveDatasources(dashboardRoot);

        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("sparxMetadata", sparxMetadata);
        combined.put("grafanaDashboard", dashboardRoot);
        combined.put("resolvedDatasources", resolvedDatasources);

        String combinedJson = objectMapper.writeValueAsString(combined);
        byte[] content = combinedJson.getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256(content);

        RawDataRefRepository.UpsertResult result = rawDataRefRepository.upsertByContentHash(
                artifactUid, TYPE, sourceId, "json", content, contentHash, content.length);

        boolean inserted = Boolean.TRUE.equals(result.getInserted());
        log.info("Stored metric-queries raw data uid={}, rawDataRefId={}, inserted={}, bytes={}",
                artifactUid, result.getId(), inserted, content.length);

        return Map.of("rawDataRefId", result.getId(), "contentHash", contentHash, "skipped", !inserted);
    }

    /**
     * §6 step 4 of adapter-spec: resolve the `${DATASOURCE}` templating variable to a real
     * datasource uid via GET /api/datasources. All three failure branches are hard errors — the
     * artifact is rejected (thrown exception fails the adapter stage, matching every other error
     * scenario in this stage).
     */
    private Map<String, Object> resolveDatasources(JsonNode dashboardRoot) {
        JsonNode templatingList = dashboardRoot.path("dashboard").path("templating").path("list");
        JsonNode datasourceVar = null;
        for (JsonNode variable : templatingList) {
            if (DATASOURCE_VARIABLE_NAME.equals(variable.path("name").asText(null))) {
                datasourceVar = variable;
                break;
            }
        }
        if (datasourceVar == null) {
            throw new IllegalStateException(
                    "metric_queries.adapter.datasource.variable_not_found: DATASOURCE variable missing in dashboard.templating.list");
        }

        String currentValue = datasourceVar.path("current").path("value").asText(null);

        List<GrafanaDatasource> datasources = datasourceCache.computeIfAbsent(DATASOURCES_CACHE_KEY, k -> {
            log.info("Fetching Grafana datasources list");
            try {
                return grafanaClient.getDatasources();
            } catch (Exception e) {
                throw new IllegalStateException("metric_queries.adapter.datasource.resolving_failed: " + e.getMessage(), e);
            }
        });

        GrafanaDatasource matched = datasources.stream()
                .filter(ds -> currentValue != null && currentValue.equals(ds.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "metric_queries.adapter.datasource.not_found_in_list: no datasource named '" + currentValue + "'"));

        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put(DATASOURCE_PLACEHOLDER, Map.of(
                "uid", matched.uid(),
                "name", matched.name(),
                "type", matched.type()));
        return resolved;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
