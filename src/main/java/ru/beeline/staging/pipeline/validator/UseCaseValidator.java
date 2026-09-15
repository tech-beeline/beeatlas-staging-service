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
import ru.beeline.staging.e2e.Finding;
import ru.beeline.staging.e2e.ParseOutcome;
import ru.beeline.staging.e2e.ParsedDiagram;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.pipeline.StageContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UseCaseValidator implements ArtifactValidator {

    public static final String MODULE_CODE = "usecase-validator";

    private static final Map<String, String> CODE_BY_PARSER_CODE = Map.of(
            "e2e.validation.syntax.invalid", "usecase.validation.syntax.invalid",
            "e2e.validation.diagram.not_sequence", "usecase.validation.diagram.not_sequence");

    private final PlantUmlDiagramParser parser;
    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Validates the UseCase PlantUML sequence diagram and import metadata"; }

    @Override
    public ValidateResult validate(String artifactUid, String rawContent, StageContext context) {
        List<ArtifactNotice> notices = new ArrayList<>();

        ParseOutcome outcome = parser.parse(rawContent);
        if (!outcome.isParsed()) {
            for (Finding finding : outcome.findings()) {
                notices.add(notice(artifactUid, CODE_BY_PARSER_CODE.getOrDefault(finding.code(),
                        "usecase.validation.syntax.invalid"), "error", finding.message(), finding.lineFrom()));
            }
        } else {
            ParsedDiagram diagram = outcome.diagram();
            if (diagram.participants().isEmpty()) {
                notices.add(notice(artifactUid, "usecase.validation.participants.missing", "error",
                        "В диаграмме нет ни одного участника", null));
            }
            if (diagram.messages().isEmpty()) {
                notices.add(notice(artifactUid, "usecase.validation.messages.empty", "warning",
                        "В диаграмме нет сообщений", null));
            }
        }

        if (context.payloadOrMissing().has("biStepCode")) {
            String biStepCode = context.payloadText("biStepCode");
            if (biStepCode == null || biStepCode.isBlank()) {
                notices.add(notice(artifactUid, "usecase.validation.bi_step.invalid", "error",
                        "Указан пустой biStepCode", null));
            }
        }
        String name = context.payloadText("name");
        if (name == null || name.isBlank()) {
            notices.add(notice(artifactUid, "usecase.validation.metadata.empty_name", "warning",
                    "Не указано имя UseCase", null));
        }

        log.info("stage=validator, module={}, uid={}, notices={}", MODULE_CODE, artifactUid, notices.size());
        return ValidateResult.of(notices);
    }

    private ArtifactNotice notice(String artifactUid, String code, String level, String message, Integer line) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", message);
        if (line != null) {
            details.put("line", line);
        }
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }
        return new ArtifactNotice(null, null, code, level, "validation", null, "usecase", artifactUid, null,
                message, detailsJson, null, null, artifactUid, null);
    }
}
