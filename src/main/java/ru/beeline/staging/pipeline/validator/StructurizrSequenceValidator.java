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
import ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class StructurizrSequenceValidator implements ArtifactValidator {

    public static final String MODULE_CODE = "structurizr-sequence-validator";

    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Validates a Structurizr workspace export: structure, and that at least one dynamic_view calls a method that exists on a product interface"; }

    @Override
    public ValidateResult validate(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);
        List<ArtifactNotice> notices = new ArrayList<>();

        if (root.path("model").isMissingNode() || root.path("views").isMissingNode()) {
            notices.add(error("structurizr-sequence.validation.structural_missing_field", "workspace.json is missing model or views", "/"));
            return ValidateResult.of(notices);
        }

        String cmdb = StructurizrParsingUtils.workspaceCmdb(root);
        if (cmdb == null || cmdb.isBlank()) {
            notices.add(error("structurizr-sequence.validation.business_missing_cmdb", "model.properties.workspace_cmdb is missing", "/model/properties/workspace_cmdb"));
            return ValidateResult.of(notices);
        }

        JsonNode targetSystem = StructurizrParsingUtils.targetSoftwareSystem(root, cmdb);
        if (targetSystem == null) {
            notices.add(error("structurizr-sequence.validation.mapping_external_reference_error",
                    "No softwareSystem with identifier=" + cmdb, "/model/softwareSystems"));
            return ValidateResult.of(notices);
        }

        JsonNode dynamicViews = root.path("views").path("dynamicViews");
        if (!dynamicViews.isArray() || dynamicViews.isEmpty()) {
            notices.add(info("structurizr-sequence.validation.no_dynamic_views", "views.dynamicViews is missing or empty — product has no sequence diagrams modeled",
                    Map.of("field", "views.dynamicViews")));
            log.info("structurizr-sequence: no dynamicViews for uid={}", artifactUid);
            return ValidateResult.of(notices);
        }

        Set<String> knownOperationKeys = StructurizrParsingUtils.collectOperationCanonicalKeys(targetSystem);
        String targetSystemId = StructurizrParsingUtils.textOrNull(targetSystem, "id");

        Set<String> seenKeys = new HashSet<>();
        boolean anyDynamicViewCallsKnownOperation = false;
        int idx = 0;
        for (JsonNode dynamicView : dynamicViews) {
            String pointer = "/views/dynamicViews/" + idx;
            idx++;

            String key = StructurizrParsingUtils.textOrNull(dynamicView, "key");
            if (key == null || key.isBlank()) {
                notices.add(warning("structurizr-sequence.validation.missing_dynamic_view_key", "dynamicView.key is missing",
                        Map.of("pointer", pointer)));
            } else if (!seenKeys.add(key)) {
                notices.add(warning("structurizr-sequence.validation.duplicate_key", "dynamicView.key is not unique within the workspace",
                        Map.of("key", key, "pointer", pointer)));
            }

            String elementId = StructurizrParsingUtils.textOrNull(dynamicView, "elementId");
            if (targetSystemId != null && !targetSystemId.equals(elementId)) {
                continue; // belongs to a different softwareSystem in the same workspace
            }

            JsonNode relationships = dynamicView.path("relationships");
            if (!relationships.isArray() || relationships.isEmpty()) {
                notices.add(warning("structurizr-sequence.validation.empty_relationships", "dynamicView has no relationships",
                        Map.of("key", String.valueOf(key), "pointer", pointer)));
                continue;
            }

            for (JsonNode relationship : relationships) {
                if (relationship.path("response").asBoolean(false)) continue;
                String description = StructurizrParsingUtils.textOrNull(relationship, "description");
                String canonicalKey = StructurizrParsingUtils.canonicalOperationKey(description);
                if (canonicalKey != null && knownOperationKeys.contains(canonicalKey)) {
                    anyDynamicViewCallsKnownOperation = true;
                    break;
                }
            }
        }

        if (!anyDynamicViewCallsKnownOperation) {
            notices.add(error("structurizr-sequence.validation.no_dynamic_view_with_known_operation",
                    "No dynamic_view calls a method that exists on an interface described in a container of the product",
                    Map.of("product", cmdb, "knownOperationCount", knownOperationKeys.size())));
        }

        if (notices.isEmpty()) {
            log.info("structurizr-sequence validation OK for uid={}", artifactUid);
            return ValidateResult.empty();
        }

        log.info("structurizr-sequence validation found {} issue(s) for uid={}", notices.size(), artifactUid);
        return ValidateResult.of(notices);
    }

    private ArtifactNotice error(String code, String message, String pointer) {
        return new ArtifactNotice(null, null, code, "error", "validation",
                null, null, null, null, message, toJson(Map.of("message", message)), pointer, null, null, null);
    }

    private ArtifactNotice error(String code, String message, Map<String, Object> details) {
        return new ArtifactNotice(null, null, code, "error", "validation",
                null, null, null, null, message, toJson(details), null, null, null, null);
    }

    private ArtifactNotice info(String code, String message, Map<String, Object> details) {
        return new ArtifactNotice(null, null, code, "info", "validation",
                null, null, null, null, message, toJson(details), null, null, null, null);
    }

    private ArtifactNotice warning(String code, String message, Map<String, Object> details) {
        return new ArtifactNotice(null, null, code, "warning", "validation",
                null, null, null, null, message, toJson(details), null, null, null, null);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
