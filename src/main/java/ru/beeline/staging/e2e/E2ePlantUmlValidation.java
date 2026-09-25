/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class E2ePlantUmlValidation {

    public static final String BI_STEP_INVALID = "e2e_plantuml.validation.bi_step.invalid";
    public static final String EMPTY_NAME = "e2e_plantuml.validation.metadata.empty_name";

    private static final String MESSAGES_EMPTY = "e2e_plantuml.validation.messages.empty";

    private static final Map<String, String> CODE_BY_ENGINE_CODE = Map.of(
            "e2e.validation.syntax.invalid", "e2e_plantuml.validation.syntax.invalid",
            "e2e.validation.diagram.not_sequence", "e2e_plantuml.validation.diagram.not_sequence",
            "e2e.validation.diagram.too_many_participants", "e2e_plantuml.validation.diagram.too_many_participants",
            "e2e.validation.participants.missing", "e2e_plantuml.validation.participants.missing",
            "e2e.validation.participant.unrecognized", "e2e_plantuml.validation.participants.unrecognized",
            "e2e.validation.call.no_rest_endpoint", "e2e_plantuml.validation.calls.unrecognized",
            "e2e.validation.call.check_failed", "e2e_plantuml.validation.calls.unrecognized",
            "e2e.validation.messages.empty", MESSAGES_EMPTY);

    private final PlantUmlValidationEngine engine;

    public EngineResult validate(String plantUml, JsonNode metadata) {
        EngineResult engineResult = engine.validate(plantUml);

        List<Finding> findings = new ArrayList<>();
        for (Finding finding : engineResult.findings()) {
            findings.add(toPipelineNotation(finding));
        }
        findings.addAll(metadataFindings(metadata));

        return new EngineResult(engineResult.recognizedParticipants(), engineResult.unrecognizedParticipants(),
                engineResult.recognizedCalls(), engineResult.unrecognizedCalls(), findings);
    }

    private Finding toPipelineNotation(Finding finding) {
        String code = CODE_BY_ENGINE_CODE.getOrDefault(finding.code(), finding.code());
        Finding.Level level = MESSAGES_EMPTY.equals(code) ? Finding.Level.WARNING : finding.level();
        return new Finding(code, level, finding.message(), finding.lineFrom(), finding.lineTo(), finding.elementRef());
    }

    private List<Finding> metadataFindings(JsonNode metadata) {
        List<Finding> findings = new ArrayList<>();
        if (metadata.has("biStepCode") && isBlank(metadata.get("biStepCode"))) {
            findings.add(Finding.error(BI_STEP_INVALID, "Указан пустой biStepCode", null, null, null));
        }
        if (isBlank(metadata.get("name"))) {
            findings.add(Finding.warning(EMPTY_NAME, "Не указано имя сценария", null, null, null));
        }
        return findings;
    }

    private static boolean isBlank(JsonNode value) {
        return value == null || value.isNull() || value.asText().isBlank();
    }
}
