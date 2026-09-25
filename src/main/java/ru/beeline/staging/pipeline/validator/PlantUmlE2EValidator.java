/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.ValidateResult;
import ru.beeline.staging.e2e.E2ePlantUmlValidation;
import ru.beeline.staging.e2e.EngineResult;
import ru.beeline.staging.e2e.Finding;
import ru.beeline.staging.pipeline.StageContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlantUmlE2EValidator implements ArtifactValidator {

    public static final String MODULE_CODE = "e2e-plantuml-validator";

    private final E2ePlantUmlValidation validation;
    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Validates the PlantUML e2e diagram against the Structurizr landscape"; }

    @Override
    public ValidateResult validate(String artifactUid, String rawContent, StageContext context) {
        EngineResult result = validation.validate(rawContent, context.payloadOrMissing());

        List<ArtifactNotice> notices = result.findings().stream()
                .map(finding -> notice(artifactUid, finding))
                .toList();

        log.info("stage=validator, module={}, uid={}, recognizedParticipants={}, recognizedCalls={}, notices={}",
                MODULE_CODE, artifactUid, result.recognizedParticipants().size(),
                result.recognizedCalls().size(), notices.size());
        return ValidateResult.of(notices);
    }

    private ArtifactNotice notice(String artifactUid, Finding finding) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", finding.message());
        if (finding.lineFrom() != null) {
            details.put("line", finding.lineFrom());
        }
        if (finding.elementRef() != null) {
            details.put("elementRef", finding.elementRef());
        }
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }
        return new ArtifactNotice(null, null, finding.code(), finding.level().name().toLowerCase(Locale.ROOT),
                "validation", null, "e2e_scenario", artifactUid, null, finding.message(), detailsJson, null, null,
                artifactUid, null);
    }
}
