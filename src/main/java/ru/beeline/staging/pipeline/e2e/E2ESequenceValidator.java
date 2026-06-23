package ru.beeline.staging.pipeline.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.ArtifactValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validator for artifactType=e2e-sequence.
 *
 * dashboard-main does not run a separate validation pass — its call-tree builder
 * (scenarios-service/build-call-tree.mjs) attaches a "validationError" array directly
 * onto each ScenarioMessage node while resolving the sequence (duplicate messages with
 * conflicting SLA, unresolved operation_guid, missing/ambiguous child diagrams, etc.),
 * and ScenarioMessage.toJSON() serializes that field as-is. So the raw JSON we already
 * downloaded in the Loader stage carries dashboard's own validation findings — we just
 * have to walk the tree and collect them, instead of re-implementing the call-tree logic.
 *
 * Mirrors dashboard's own behaviour: issues are collected and logged as warnings, never
 * treated as fatal (dashboard's UI shows them as embedded warnings, not request failures).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class E2ESequenceValidator implements ArtifactValidator {

    private final ObjectMapper objectMapper;

    @Override
    public String supportedType() { return DashboardE2ELoader.TYPE; }

    @Override
    public Map<String, Object> validate(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);

        List<String> warnings = new ArrayList<>();
        collectValidationErrors(root.path("sequence"), warnings);

        if (warnings.isEmpty()) {
            log.info("e2e-sequence validation OK for uid={}", artifactUid);
            return null;
        }

        log.warn("e2e-sequence validation found {} issue(s) for uid={}: {}", warnings.size(), artifactUid, warnings);
        return Map.of("validationWarningsCount", warnings.size());
    }

    private void collectValidationErrors(JsonNode messages, List<String> out) {
        if (messages == null || !messages.isArray()) {
            return;
        }
        for (JsonNode message : messages) {
            JsonNode errors = message.path("validationError");
            if (errors.isArray()) {
                for (JsonNode error : errors) {
                    out.add(error.asText());
                }
            }
            collectValidationErrors(message.path("sequence"), out);
        }
    }
}
