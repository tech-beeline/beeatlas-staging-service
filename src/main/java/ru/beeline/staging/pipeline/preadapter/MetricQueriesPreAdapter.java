package ru.beeline.staging.pipeline.preadapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.sparx.MetricQueriesEntityTypeResolver;
import ru.beeline.staging.sparx.SparxMetricQueriesRepository;
import ru.beeline.staging.sparx.dto.MetricQueriesSourceMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ported from documentation/staging-service/source-artefacts/metric-queries/metric-queries-preadapter-spec.md
 * — keep in sync with that spec. Scans Sparx EA for objects carrying property 'api-metric-template'.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueriesPreAdapter implements ArtifactPreAdapter {

    public static final String MODULE_CODE = "metric-queries-preadapter";

    private final SparxMetricQueriesRepository sparxMetricQueriesRepository;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Scans Sparx EA for objects carrying property api-metric-template"; }

    @Override
    public List<FoundArtifact> scan(Configuration config) {
        List<MetricQueriesSourceMeta> rows = sparxMetricQueriesRepository.findAll();
        log.info("Sparx scan metric-queries: found {} objects with api-metric-template for configId={}",
                rows.size(), config.getId());

        List<FoundArtifact> found = new ArrayList<>();
        for (MetricQueriesSourceMeta row : rows) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("entity_type", MetricQueriesEntityTypeResolver.resolve(row));
            metadata.put("name", row.getName());
            metadata.put("apiMetricTemplateUrl", row.getApiMetricTemplate());
            found.add(new FoundArtifact(row.getUid(), metadata));
        }

        // P4 (duplicate_uid, §6 step 7): a duplicate_uid notice would normally go through
        // ArtifactNoticeService, but that requires a rawDataRefId (via raw_data_context) which
        // doesn't exist until the adapter stage runs for a specific artifact — pre-adapter has no
        // raw data to attach it to. Logged instead; actual dedup safety is the uid UNIQUE
        // constraint on metric_query_templates (find-or-create on the saver).
        Map<String, Long> countByUid = found.stream()
                .collect(Collectors.groupingBy(FoundArtifact::uid, Collectors.counting()));
        countByUid.forEach((uid, count) -> {
            if (count > 1) {
                log.warn("metric-queries pre-adapter: duplicate uid={} ({} colliding Sparx objects, alias collision)",
                        uid, count);
            }
        });

        return found;
    }
}
