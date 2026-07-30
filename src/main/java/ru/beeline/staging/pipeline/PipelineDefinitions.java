package ru.beeline.staging.pipeline;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import ru.beeline.staging.pipeline.adapter.SparxE2EAdapter;
import ru.beeline.staging.pipeline.adapter.StructurizrSequenceAdapter;
import ru.beeline.staging.pipeline.preadapter.SparxE2EPreAdapter;
import ru.beeline.staging.pipeline.preadapter.StructurizrSequencePreAdapter;
import ru.beeline.staging.pipeline.saver.E2ECanonicalSaver;
import ru.beeline.staging.pipeline.saver.StructurizrSequenceCanonicalSaver;
import ru.beeline.staging.pipeline.transformer.E2ESequenceTransformer;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceTransformer;
import ru.beeline.staging.pipeline.validator.E2ESequenceValidator;
import ru.beeline.staging.pipeline.validator.StructurizrSequenceValidator;

@Component
public class PipelineDefinitions {

        public static final List<String> STAGE_ORDER = List.of("pre-adapter", "adapter", "validator", "transformer",
                        "saver");

        private static final Map<String, Map<String, String>> DEFINITIONS = Map.of(

                        "e2e-sequence", Map.of(
                                        "pre-adapter", SparxE2EPreAdapter.MODULE_CODE,
                                        "adapter", SparxE2EAdapter.MODULE_CODE,
                                        "validator", E2ESequenceValidator.MODULE_CODE,
                                        "transformer", E2ESequenceTransformer.MODULE_CODE,
                                        "saver", E2ECanonicalSaver.MODULE_CODE),
                        "structurizr-sequence", Map.of(
                                        "pre-adapter", StructurizrSequencePreAdapter.MODULE_CODE,
                                        "adapter", StructurizrSequenceAdapter.MODULE_CODE,
                                        "validator", StructurizrSequenceValidator.MODULE_CODE,
                                        "transformer", StructurizrSequenceTransformer.MODULE_CODE,
                                        "saver", StructurizrSequenceCanonicalSaver.MODULE_CODE));

        public Map<String, String> moduleMapFor(String artifactType) {
                return DEFINITIONS.get(artifactType);
        }

        public Map<String, Map<String, String>> all() {
                return DEFINITIONS;
        }
}
