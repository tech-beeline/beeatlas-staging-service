package ru.beeline.staging.pipeline.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.ValidateResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural validation of a Structurizr workspace export, per SQ.01-SQ.02 in
 * structurizr-sequence-extract.md. Deeper semantic checks (dangling relationship refs, unresolved
 * elements) happen in the transformer, where they can be pinned to the exact dynamicView/relationship.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructurizrSequenceValidator implements ArtifactValidator {

    public static final String MODULE_CODE = "structurizr-sequence-validator";

    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Structurally validates a Structurizr workspace export's views.dynamicViews section"; }

    @Override
    public ValidateResult validate(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);
        List<ArtifactNotice> notices = new ArrayList<>();

        JsonNode dynamicViews = root.path("views").path("dynamicViews");
        if (!dynamicViews.isArray()) {
            // Not an anomaly: most products only model C4 containers/components in Structurizr and
            // never draw a single Dynamic View — the pre-adapter scans every product with a
            // structurizrApiUrl, not just ones known to have sequence diagrams. Nothing to load this
            // time, not a validation failure — see the artifact-level error path below for what
            // actually deserves that.
            notices.add(info("structurizr-sequence.validation.no_dynamic_views", "views.dynamicViews is missing or not an array — product has no sequence diagrams modeled",
                    Map.of("field", "views.dynamicViews")));
            log.info("structurizr-sequence: no dynamicViews for uid={}", artifactUid);
            return ValidateResult.of(notices);
        }

        Set<String> seenKeys = new HashSet<>();
        int idx = 0;
        for (JsonNode dynamicView : dynamicViews) {
            String pointer = "/views/dynamicViews/" + idx;
            String key = textOrNull(dynamicView, "key");
            if (key == null || key.isBlank()) {
                // Not an artifact-level error: the transformer already skips a dynamicView with no key
                // on its own (see StructurizrDynamicViewDecomposer) without losing the rest of the
                // snapshot, so this must not fail the whole run the way ValidatorWorker treats "error".
                notices.add(warning("structurizr-sequence.validation.missing_dynamic_view_key", "dynamicView.key is missing",
                        Map.of("pointer", pointer)));
            } else if (!seenKeys.add(key)) {
                notices.add(warning("structurizr-sequence.validation.duplicate_key", "dynamicView.key is not unique within the workspace",
                        Map.of("key", key, "pointer", pointer)));
            }

            JsonNode relationships = dynamicView.path("relationships");
            if (!relationships.isArray() || relationships.isEmpty()) {
                notices.add(warning("structurizr-sequence.validation.empty_relationships", "dynamicView has no relationships",
                        Map.of("key", String.valueOf(key), "pointer", pointer)));
            }
            idx++;
        }

        if (notices.isEmpty()) {
            log.info("structurizr-sequence validation OK for uid={}", artifactUid);
            return ValidateResult.empty();
        }

        log.warn("structurizr-sequence validation found {} issue(s) for uid={}", notices.size(), artifactUid);
        return ValidateResult.of(notices);
    }

    private ArtifactNotice info(String code, String message, Map<String, Object> details) {
        return new ArtifactNotice(null, null, code, "info", "validation",
                null, null, null, null, message, toJson(details), null, null);
    }

    private ArtifactNotice warning(String code, String message, Map<String, Object> details) {
        return new ArtifactNotice(null, null, code, "warning", "validation",
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
