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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Structural validation of the raw Sparx EA scenario export. Semantic checks (missing references,
 * ambiguous diagram links, etc.) happen in ScenarioDecomposer/transform instead, where they can be
 * pinned to the exact fragment that failed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class E2ESequenceValidator implements ArtifactValidator {

    public static final String MODULE_CODE = "e2e-sequence-validator";

    private static final List<String> REQUIRED_ARRAY_BLOCKS =
            List.of("diagrams", "objects", "systems", "interfaces", "operations");

    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Structurally validates the raw Sparx EA scenario export"; }

    @Override
    public ValidateResult validate(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);
        List<ArtifactNotice> notices = new ArrayList<>();

        String entranceDiagramUid = textOrNull(root, "entrance_diagram_uid");
        if (entranceDiagramUid == null || entranceDiagramUid.isBlank()) {
            notices.add(error("validation.missing_required_field", "entrance_diagram_uid is missing",
                    Map.of("field", "entrance_diagram_uid")));
        }

        for (String block : REQUIRED_ARRAY_BLOCKS) {
            if (!root.path(block).isArray()) {
                notices.add(error("validation.missing_required_field", block + " is missing or not an array",
                        Map.of("field", block)));
            }
        }

        if (entranceDiagramUid != null && root.path("diagrams").isArray()) {
            boolean found = false;
            for (JsonNode diagram : root.path("diagrams")) {
                if (entranceDiagramUid.equals(textOrNull(diagram, "uid"))) { found = true; break; }
            }
            if (!found) {
                notices.add(error("validation.invalid_format", "entrance_diagram_uid does not resolve among diagrams[].uid",
                        Map.of("entrance_diagram_uid", entranceDiagramUid)));
            }
        }

        if (notices.isEmpty()) {
            log.info("e2e-sequence validation OK for uid={}", artifactUid);
            return ValidateResult.empty();
        }

        log.warn("e2e-sequence validation found {} issue(s) for uid={}", notices.size(), artifactUid);
        return ValidateResult.of(notices);
    }

    private ArtifactNotice error(String code, String message, Map<String, Object> details) {
        return new ArtifactNotice(null, null, code, "error", "validation",
                null, null, null, null, message, toJson(details), null, null);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
