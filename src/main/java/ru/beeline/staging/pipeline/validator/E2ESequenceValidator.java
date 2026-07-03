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
    public ValidateResult validate(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);

        List<String> warnings = new ArrayList<>();
        collectValidationErrors(root.path("sequence"), warnings);

        if (warnings.isEmpty()) {
            log.info("e2e-sequence validation OK for uid={}", artifactUid);
            return ValidateResult.empty();
        }

        log.warn("e2e-sequence validation found {} issue(s) for uid={}: {}", warnings.size(), artifactUid, warnings);

        String context = toJson(Map.of("stage", "validator", "artifact_uid", artifactUid));
        List<ArtifactNotice> notices = warnings.stream().map(w -> new ArtifactNotice(
                null, null,
                "validation.e2e_sequence.embedded_error",
                "warning",
                "validation",
                null,
                null, null, null,
                w,
                null,
                context
        )).toList();

        return ValidateResult.of(notices);
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

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
