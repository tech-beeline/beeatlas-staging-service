package ru.beeline.staging.pipeline.preadapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.sparx.SparxE2ERepository;
import ru.beeline.staging.sparx.dto.E2EScenarioMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans Sparx EA for e2e diagrams and starts one artifact-pipeline-process per scenario
 * found (directly, via PipelineRunService — no broker hop needed for a purely internal
 * fan-out). Shared logic for both the scheduled pre-adapter-process tick and any future
 * manual trigger for this module.
 *
 * "Change detection" happens downstream, not here: every scan finds every scenario UID
 * present in Sparx, and the adapter stage's content-hash comparison against the last
 * raw_data_ref decides whether anything actually changed. Sparx EA does not expose a
 * reliable per-diagram modified timestamp we could filter on here.
 *
 * Crash safety: each scenario's process instance is started independently. If the service
 * crashes mid-loop, items already started are durably running in Camunda (its own state is
 * in Postgres); items not yet reached are simply found again, unstarted, on the next
 * PipelineTickScheduler tick — no item is ever marked "consumed" before its process exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SparxE2EPreAdapter implements ArtifactPreAdapter {

    public static final String MODULE_CODE = "sparx-e2e-preadapter";

    private final SparxE2ERepository  sparxE2ERepository;
    private final PipelineRunService  pipelineRunService;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Scans Sparx EA for e2e diagrams and starts one pipeline run per scenario"; }

    @Override
    public int scanAndPublish(Configuration config, String batchId) {
        List<E2EScenarioMeta> scenarios = sparxE2ERepository.findAllScenarios();
        log.info("Sparx scan e2e-sequence: found {} scenarios for configId={}", scenarios.size(), config.getId());

        for (E2EScenarioMeta scenario : scenarios) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("name",        scenario.getName());
            metadata.put("version",     scenario.getVersion());
            metadata.put("processUid",  scenario.getProcessUid());
            metadata.put("processName", scenario.getProcessName());
            metadata.put("notes",       scenario.getNotes());

            pipelineRunService.startArtifactPipeline(
                    config.getId(), config.getArtifactType(), scenario.getUid(), batchId, metadata);
        }
        return scenarios.size();
    }
}
