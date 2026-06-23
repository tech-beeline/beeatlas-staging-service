package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.sparx.SparxE2ERepository;
import ru.beeline.staging.sparx.dto.E2EScenarioMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scans Sparx EA for e2e diagrams and publishes one staging.events message per scenario
 * found. Shared by the Camunda-driven pre-adapter (timer every staging.camunda.timer.
 * pre-adapter-sparx-cycle) and the manual REST trigger (POST /admin/scan/e2e) so both
 * entry points run the exact same logic.
 *
 * "Change detection" happens downstream, not here: every scan re-publishes every scenario
 * UID found in Sparx, and the Loader stage's content-hash comparison against the last
 * raw_data_ref decides whether anything actually changed (skips the S3 write/transform/save
 * if it didn't). Sparx EA does not expose a reliable per-diagram modified timestamp we could
 * use to filter here, so this is intentionally the simpler, downstream-deduped approach.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SparxScanService {

    private final ConfigurationRepository configurationRepository;
    private final PipelineService         pipelineService;
    private final SparxE2ERepository      sparxE2ERepository;

    /** Used by the manual/admin trigger: scans for all active e2e-sequence configurations. */
    public int scanAllActiveE2EConfigurations() {
        List<Configuration> configs = configurationRepository.findByArtifactTypeAndIsActiveTrue("e2e-sequence");
        String batchId = UUID.randomUUID().toString();
        int total = 0;
        for (Configuration config : configs) {
            total += scanAndPublishForConfig(config, batchId);
        }
        return total;
    }

    /** Used by PreAdapterWorker for a single configuration already selected by the caller. */
    public int scanAndPublishForConfig(Configuration config, String batchId) {
        if (sparxE2ERepository == null) {
            log.warn("Sparx datasource not configured — skipping e2e-sequence scan for configId={}", config.getId());
            return 0;
        }

        List<E2EScenarioMeta> scenarios = sparxE2ERepository.findAllScenarios();
        log.info("Sparx scan e2e-sequence: found {} scenarios for configId={}", scenarios.size(), config.getId());

        for (E2EScenarioMeta scenario : scenarios) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("name",        scenario.getName());
            metadata.put("version",     scenario.getVersion());
            metadata.put("processUid",  scenario.getProcessUid());
            metadata.put("processName", scenario.getProcessName());
            metadata.put("notes",       scenario.getNotes());

            pipelineService.publishArtifactEvent(config, batchId, scenario.getUid(), metadata);
        }
        return scenarios.size();
    }
}
