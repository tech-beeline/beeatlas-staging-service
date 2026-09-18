/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.ValidateResult;
import ru.beeline.staging.e2e.EngineResult;
import ru.beeline.staging.e2e.Finding;
import ru.beeline.staging.e2e.PlantUmlValidationEngine;
import ru.beeline.staging.pipeline.StageContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlantUmlE2EValidator implements ArtifactValidator {

    public static final String MODULE_CODE = "e2e-plantuml-validator";

    private static final Map<String, String> CODE_BY_ENGINE_CODE = Map.of(
            "e2e.validation.syntax.invalid", "e2e_plantuml.validation.syntax.invalid",
            "e2e.validation.diagram.not_sequence", "e2e_plantuml.validation.diagram.not_sequence",
            "e2e.validation.diagram.too_many_participants", "e2e_plantuml.validation.diagram.too_many_participants",
            "e2e.validation.participants.missing", "e2e_plantuml.validation.participants.missing",
            "e2e.validation.participant.unrecognized", "e2e_plantuml.validation.participants.unrecognized",
            "e2e.validation.participant.ambiguous", "e2e_plantuml.validation.participants.ambiguous",
            "e2e.validation.call.no_rest_endpoint", "e2e_plantuml.validation.calls.unrecognized",
            "e2e.validation.call.check_failed", "e2e_plantuml.validation.calls.unrecognized",
            "e2e.validation.messages.empty", "e2e_plantuml.validation.messages.empty");

    private final PlantUmlValidationEngine validationEngine;
    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Validates the PlantUML e2e diagram against the Structurizr landscape"; }

    @Override
    public ValidateResult validate(String artifactUid, String rawContent, StageContext context) {
        EngineResult engineResult = validationEngine.validate(rawContent);

        List<ArtifactNotice> notices = new ArrayList<>();
        for (Finding finding : engineResult.findings()) {
            notices.add(fromFinding(artifactUid, finding));
        }
        notices.addAll(payloadNotices(artifactUid, context));

        log.info("stage=validator, module={}, uid={}, recognizedParticipants={}, recognizedCalls={}, notices={}",
                MODULE_CODE, artifactUid, engineResult.recognizedParticipants().size(),
                engineResult.recognizedCalls().size(), notices.size());
        return ValidateResult.of(notices);
    }

    private List<ArtifactNotice> payloadNotices(String artifactUid, StageContext context) {
        List<ArtifactNotice> notices = new ArrayList<>();
        JsonNode payload = context.payloadOrMissing();

        if (payload.has("biStepCode")) {
            String biStepCode = context.payloadText("biStepCode");
            if (biStepCode == null || biStepCode.isBlank()) {
                notices.add(notice(artifactUid, "e2e_plantuml.validation.bi_step.invalid", "error",
                        "Указан пустой biStepCode", null, null));
            }
        }
        String name = context.payloadText("name");
        if (name == null || name.isBlank()) {
            notices.add(notice(artifactUid, "e2e_plantuml.validation.metadata.empty_name", "warning",
                    "Не указано имя сценария", null, null));
        }
        return notices;
    }

    private ArtifactNotice fromFinding(String artifactUid, Finding finding) {
        String code = CODE_BY_ENGINE_CODE.getOrDefault(finding.code(), finding.code());
        String level = "e2e_plantuml.validation.messages.empty".equals(code)
                ? "warning"
                : finding.level().name().toLowerCase(Locale.ROOT);
        return notice(artifactUid, code, level, finding.message(), finding.lineFrom(), finding.elementRef());
    }

    private ArtifactNotice notice(String artifactUid, String code, String level, String message,
            Integer line, String elementRef) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", message);
        if (line != null) {
            details.put("line", line);
        }
        if (elementRef != null) {
            details.put("elementRef", elementRef);
        }
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }
        return new ArtifactNotice(null, null, code, level, "validation", null, "e2e_scenario", artifactUid, null,
                message, detailsJson, null, null, artifactUid, null);
    }
}
