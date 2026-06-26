package ru.beeline.staging.pipeline.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class E2ESequenceValidator implements ArtifactValidator {

    public static final String MODULE_CODE = "e2e-sequence-validator";

    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Collects dashboard's own embedded validation warnings from the e2e sequence JSON"; }

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
