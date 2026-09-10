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
