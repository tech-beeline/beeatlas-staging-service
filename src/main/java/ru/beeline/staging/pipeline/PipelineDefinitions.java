package ru.beeline.staging.pipeline;

import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.adapter.DashboardE2EAdapter;
import ru.beeline.staging.pipeline.preadapter.SparxE2EPreAdapter;
import ru.beeline.staging.pipeline.saver.E2ECanonicalSaver;
import ru.beeline.staging.pipeline.transformer.E2ESequenceTransformer;
import ru.beeline.staging.pipeline.validator.E2ESequenceValidator;

import java.util.List;
import java.util.Map;

/**
 * Code-defined set of modules per artifactType — replaces the old configurations.config
 * JSONB column. To add a new entity type: write its module beans, then add one entry to
 * DEFINITIONS below (stage topic name -> moduleCode). Visible before any pipeline run via
 * staging.pipeline_definitions (see ModuleCatalogPublisher), which mirrors this map into
 * the DB on every startup.
 */
@Component
public class PipelineDefinitions {

    /** Fixed BPMN execution order — Map.of() below does not preserve insertion order. */
    public static final List<String> STAGE_ORDER =
            List.of("pre-adapter", "adapter", "validator", "transformer", "saver");

    private static final Map<String, Map<String, String>> DEFINITIONS = Map.of(

            "e2e-sequence", Map.of(
                    "pre-adapter", SparxE2EPreAdapter.MODULE_CODE,
                    "adapter",     DashboardE2EAdapter.MODULE_CODE,
                    "validator",   E2ESequenceValidator.MODULE_CODE,
                    "transformer", E2ESequenceTransformer.MODULE_CODE,
                    "saver",       E2ECanonicalSaver.MODULE_CODE
            )

            // new entity type = one more "artifactType", Map.of(...) entry here
    );

    public Map<String, String> moduleMapFor(String artifactType) {
        return DEFINITIONS.get(artifactType);
    }

    public Map<String, Map<String, String>> all() {
        return DEFINITIONS;
    }
}
