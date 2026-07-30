package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.ContainerDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.InterfaceDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.OperationDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.OperationRelationDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.ProductDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.SequenceDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.SequenceRelationDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.TechCapabilityDraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils;
import ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils.IndexedNode;

import static ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils.canonicalOperationKey;
import static ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils.firstNonBlank;
import static ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils.httpMethodOf;
import static ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils.normalizeForUid;
import static ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils.parseSlaProperties;
import static ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils.textOrNull;
import static ru.beeline.staging.pipeline.structurizr.StructurizrParsingUtils.workspaceCmdb;

/**
 * Decomposes a Structurizr workspace.json into a {@link StructurizrSequenceSnapshot}, following the
 * extraction order and field rules of structurizr-sequence-transform-rules.md: product -> containers
 * -> tech_capabilities -> interfaces -> operations -> sequences -> sequence_relations ->
 * operation_relations.
 */
@Component
@RequiredArgsConstructor
public class StructurizrDynamicViewDecomposer {

    private final ObjectMapper objectMapper;

    public record Result(StructurizrSequenceSnapshot snapshot, List<ArtifactNotice> notices) {}

    private record RelationshipInfo(String sourceId, String destinationId, String description) {}
    private record ContainerRef(JsonNode node, String pointer) {}

    public Result decompose(JsonNode root, String artifactUid) {
        List<ArtifactNotice> notices = new ArrayList<>();
        StructurizrSequenceSnapshot snapshot = new StructurizrSequenceSnapshot();

        String cmdb = workspaceCmdb(root);
        if (cmdb == null || cmdb.isBlank()) {
            notices.add(error("extract.cmdb_missing", "model.properties.workspace_cmdb is missing", "/model/properties/workspace_cmdb"));
            return new Result(snapshot, notices);
        }

        IndexedNode targetEntry = StructurizrParsingUtils.targetSoftwareSystemEntry(root, cmdb);
        if (targetEntry == null) {
            notices.add(error("extract.target_system_missing", "No softwareSystem with identifier=" + cmdb, "/model/softwareSystems"));
            return new Result(snapshot, notices);
        }
        JsonNode targetSystem = targetEntry.node();
        String systemPointer = "/model/softwareSystems/" + targetEntry.index();

        snapshot.setProduct(extractProduct(root, targetSystem, cmdb, notices));

        Map<String, ContainerRef> containerRefsByUid = new LinkedHashMap<>();
        extractContainers(targetSystem, systemPointer, cmdb, snapshot, containerRefsByUid, notices);

        Map<String, TechCapabilityDraft> tcByUid = new LinkedHashMap<>();
        extractTechCapabilities(containerRefsByUid, cmdb, snapshot, tcByUid, notices);

        Map<String, String> operationUidByCanonicalKey = new LinkedHashMap<>();
        extractInterfacesAndOperations(containerRefsByUid, cmdb, tcByUid, snapshot, operationUidByCanonicalKey, notices);

        Map<String, RelationshipInfo> relationshipsById = new LinkedHashMap<>();
        indexModel(root.path("model"), relationshipsById);

        String targetSystemId = textOrNull(targetSystem, "id");
        extractSequences(root, targetSystemId, cmdb, tcByUid, operationUidByCanonicalKey, relationshipsById, snapshot, notices);

        return new Result(snapshot, notices);
    }

    // ---------------------------------------------------------------- product

    private ProductDraft extractProduct(JsonNode root, JsonNode targetSystem, String cmdb, List<ArtifactNotice> notices) {
        ProductDraft product = new ProductDraft();
        product.setUid(cmdb);
        product.setExtUid(cmdb);
        product.setContext("/model/properties/workspace_cmdb");

        String name = textOrNull(targetSystem, "name");
        if (name == null) notices.add(error("extract.product_missing_name", "Target softwareSystem has no name", "/model/softwareSystems"));
        product.setName(name);

        String description = textOrNull(targetSystem, "description");
        if (description == null) notices.add(info("extract.description_missing", "Product description is missing", "/model/softwareSystems"));
        product.setDescription(description);

        String author = textOrNull(root.path("model").path("properties"), "architect");
        if (author == null) notices.add(warning("extract.author_missing", "model.properties.architect is missing", "/model/properties/architect"));
        product.setAuthor(author);
        return product;
    }

    // ---------------------------------------------------------------- containers

    private void extractContainers(JsonNode targetSystem, String systemPointer, String cmdb, StructurizrSequenceSnapshot snapshot,
                                    Map<String, ContainerRef> containerRefsByUid, List<ArtifactNotice> notices) {
        JsonNode containers = targetSystem.path("containers");
        if (!containers.isArray() || containers.isEmpty()) {
            notices.add(warning("extract.containers_missing", "No containers on target softwareSystem", systemPointer));
            return;
        }

        int idx = 0;
        for (JsonNode container : containers) {
            String pointer = systemPointer + "/containers/" + idx;
            idx++;

            String uid = textOrNull(container.path("properties"), "external_name");
            if (uid == null || uid.isBlank()) {
                notices.add(error("extract.container_external_name_missing", "Container has no properties.external_name", pointer));
                continue;
            }
            String name = textOrNull(container, "name");
            if (name == null || name.isBlank()) {
                notices.add(error("extract.container_missing_name", "Container '" + uid + "' has no name", pointer));
                continue;
            }

            String technology = textOrNull(container, "technology");
            if (technology == null) notices.add(warning("extract.container_missing_technology", "Container '" + uid + "' has no technology", pointer));

            String description = textOrNull(container, "description");
            if (description == null) notices.add(info("extract.container_description_missing", "Container '" + uid + "' has no description", pointer));

            String version = textOrNull(container.path("properties"), "version");
            if (version == null) notices.add(info("extract.container_version_missing", "Container '" + uid + "' has no properties.version", pointer));

            ContainerDraft draft = new ContainerDraft();
            draft.setUid(uid);
            draft.setExtUid(uid);
            draft.setName(name);
            draft.setVersion(version);
            draft.setDescription(description);
            draft.setTechnology(technology);
            draft.setContext(pointer);
            snapshot.getContainers().add(draft);
            containerRefsByUid.put(uid, new ContainerRef(container, pointer));
        }
    }

    // ---------------------------------------------------------------- tech capabilities

    private void extractTechCapabilities(Map<String, ContainerRef> containerRefsByUid, String cmdb,
                                          StructurizrSequenceSnapshot snapshot, Map<String, TechCapabilityDraft> tcByUid,
                                          List<ArtifactNotice> notices) {
        boolean anyFound = false;
        for (ContainerRef containerRef : containerRefsByUid.values()) {
            int idx = 0;
            for (JsonNode component : containerRef.node().path("components")) {
                if (!"capability".equals(textOrNull(component, "type"))) continue;
                anyFound = true;
                String pointer = containerRef.pointer() + "/components/" + idx;
                idx++;

                String code = textOrNull(component.path("properties"), "code");
                if (code == null || code.isBlank()) {
                    notices.add(error("extract.tc_code_missing", "Capability component has no properties.code", pointer));
                    continue;
                }
                String name = textOrNull(component, "name");
                if (name == null || name.isBlank()) {
                    notices.add(error("extract.tc_missing_name", "Capability '" + code + "' has no name", pointer));
                    continue;
                }
                if (textOrNull(component.path("properties"), "parents") == null) {
                    notices.add(warning("extract.tc_parents_missing", "Capability '" + code + "' has no properties.parents", pointer));
                }
                String description = textOrNull(component, "description");
                if (description == null) notices.add(info("extract.tc_description_missing", "Capability '" + code + "' has no description", pointer));

                String uid = cmdb + "." + code;
                TechCapabilityDraft draft = new TechCapabilityDraft();
                draft.setUid(uid);
                draft.setExtUid(uid);
                draft.setName(name);
                draft.setDescription(description);
                draft.setContext(pointer);
                snapshot.getTechCapabilities().add(draft);
                tcByUid.put(uid, draft);
            }
        }
        if (!anyFound) {
            notices.add(warning("extract.tc_missing", "No type=capability components found", "/model/softwareSystems"));
        }
    }

    // ---------------------------------------------------------------- interfaces + operations

    private void extractInterfacesAndOperations(Map<String, ContainerRef> containerRefsByUid, String cmdb,
                                                 Map<String, TechCapabilityDraft> tcByUid,
                                                 StructurizrSequenceSnapshot snapshot,
                                                 Map<String, String> operationUidByCanonicalKey,
                                                 List<ArtifactNotice> notices) {
        boolean anyInterfaceFound = false;
        for (Map.Entry<String, ContainerRef> entry : containerRefsByUid.entrySet()) {
            String containerUid = entry.getKey();
            int idx = 0;
            for (JsonNode component : entry.getValue().node().path("components")) {
                if (!"api".equals(textOrNull(component, "type"))) continue;
                anyInterfaceFound = true;
                String pointer = entry.getValue().pointer() + "/components/" + idx;
                idx++;

                JsonNode properties = component.path("properties");
                String ifaceUid = textOrNull(properties, "external_name");
                if (ifaceUid == null || ifaceUid.isBlank()) {
                    notices.add(error("extract.interface_external_name_missing", "API component has no properties.external_name", pointer));
                    continue;
                }
                String name = textOrNull(component, "name");
                if (name == null || name.isBlank()) {
                    notices.add(error("extract.interface_missing_name", "Interface '" + ifaceUid + "' has no name", pointer));
                    continue;
                }

                String protocol = textOrNull(properties, "protocol");
                if (protocol == null) {
                    protocol = "REST";
                    notices.add(info("extract.interface_protocol_default", "Interface '" + ifaceUid + "' has no properties.protocol, defaulting to REST", pointer));
                }
                String specLink = textOrNull(properties, "api_url");
                if (specLink == null) notices.add(warning("extract.interface_spec_link_missing", "Interface '" + ifaceUid + "' has no properties.api_url", pointer));

                String version = textOrNull(properties, "version");
                if (version == null) notices.add(warning("extract.interface_version_missing", "Interface '" + ifaceUid + "' has no properties.version", pointer));

                String description = textOrNull(component, "description");
                if (description == null) notices.add(info("extract.interface_description_missing", "Interface '" + ifaceUid + "' has no description", pointer));

                InterfaceDraft iface = new InterfaceDraft();
                iface.setUid(ifaceUid);
                iface.setExtUid(ifaceUid);
                iface.setName(name);
                iface.setProtocol(protocol);
                iface.setSpecLink(specLink);
                iface.setVersion(version);
                iface.setDescription(description);
                iface.setContainerUid(containerUid);
                iface.setContext(pointer);
                snapshot.getInterfaces().add(iface);

                extractOperations(properties, ifaceUid, cmdb, tcByUid, snapshot, operationUidByCanonicalKey, pointer, notices);
            }
        }
        if (!anyInterfaceFound) {
            notices.add(warning("extract.interfaces_missing", "No type=api components found", "/model/softwareSystems"));
        }
    }

    private void extractOperations(JsonNode interfaceProperties, String interfaceUid, String cmdb,
                                    Map<String, TechCapabilityDraft> tcByUid, StructurizrSequenceSnapshot snapshot,
                                    Map<String, String> operationUidByCanonicalKey, String interfacePointer,
                                    List<ArtifactNotice> notices) {
        boolean anyOperation = false;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = interfaceProperties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String operationName = field.getKey();
            if (StructurizrParsingUtils.RESERVED_INTERFACE_PROPERTY_KEYS.contains(operationName.toLowerCase())) continue;
            if (operationName.isBlank()) {
                notices.add(error("extract.operation_name_missing", "Empty operation property key on interface " + interfaceUid, interfacePointer));
                continue;
            }
            anyOperation = true;
            String pointer = interfacePointer + "/properties/" + operationName;

            String type = httpMethodOf(operationName);
            if (type == null) notices.add(warning("extract.operation_type_unknown", "Could not extract HTTP method from '" + operationName + "'", pointer));

            Map<String, String> sla = parseSlaProperties(field.getValue().isTextual() ? field.getValue().asText() : null);
            if (field.getValue().isTextual() && !field.getValue().asText().isBlank() && sla.isEmpty()) {
                notices.add(warning("extract.operation_sla_parse_error", "Could not parse SLA value for '" + operationName + "'", pointer));
            }

            Double rps = parseNumeric(sla.get("RPS"), operationName, "RPS", pointer, notices);
            if (!sla.containsKey("RPS")) notices.add(info("extract.operation_rps_missing", "No RPS for '" + operationName + "'", pointer));

            Double latency = parseNumeric(sla.get("LATENCY"), operationName, "LATENCY", pointer, notices);
            if (!sla.containsKey("LATENCY")) notices.add(info("extract.operation_latency_missing", "No LATENCY for '" + operationName + "'", pointer));

            Double errorRate = parseNumeric(sla.get("ERROR_RATE"), operationName, "ERROR_RATE", pointer, notices);
            if (!sla.containsKey("ERROR_RATE")) notices.add(info("extract.operation_error_rate_missing", "No ERROR_RATE for '" + operationName + "'", pointer));

            String tcRef = sla.get("TC");
            String tcUid = null;
            if (tcRef != null && !tcRef.isBlank()) {
                tcUid = tcRef.contains(".") ? tcRef : cmdb + "." + tcRef;
                if (!tcByUid.containsKey(tcUid)) {
                    notices.add(warning("extract.operation_tc_not_found", "TC '" + tcUid + "' referenced by operation '" + operationName + "' not found", pointer));
                    tcUid = null;
                }
            }

            String uid = interfaceUid + "_" + normalizeForUid(operationName);
            OperationDraft operation = new OperationDraft();
            operation.setUid(uid);
            operation.setExtUid(uid);
            operation.setName(operationName);
            operation.setType(type);
            operation.setRps(rps);
            operation.setLatency(latency);
            operation.setErrorRate(errorRate);
            operation.setInterfaceUid(interfaceUid);
            operation.setTechCapabilityUid(tcUid);
            operation.setContext(pointer);
            snapshot.getOperations().add(operation);
            operationUidByCanonicalKey.put(canonicalOperationKey(operationName), uid);
        }
        if (!anyOperation) {
            notices.add(warning("extract.operations_missing", "No operation properties on interface " + interfaceUid, interfacePointer));
        }
    }

    private Double parseNumeric(String rawValue, String operationName, String key, String pointer, List<ArtifactNotice> notices) {
        if (rawValue == null || rawValue.isBlank()) return null;
        try {
            return Double.parseDouble(rawValue);
        } catch (NumberFormatException e) {
            notices.add(warning("extract.operation_sla_parse_error", "Non-numeric " + key + " value '" + rawValue + "' for '" + operationName + "'", pointer));
            return null;
        }
    }

    // ---------------------------------------------------------------- sequences + relations

    private void extractSequences(JsonNode root, String targetSystemId, String cmdb,
                                   Map<String, TechCapabilityDraft> tcByUid, Map<String, String> operationUidByCanonicalKey,
                                   Map<String, RelationshipInfo> relationshipsById, StructurizrSequenceSnapshot snapshot,
                                   List<ArtifactNotice> notices) {
        List<JsonNode> dynamicViews = new ArrayList<>();
        for (JsonNode view : root.path("views").path("dynamicViews")) {
            String elementId = textOrNull(view, "elementId");
            if (targetSystemId != null && targetSystemId.equals(elementId)) dynamicViews.add(view);
        }
        if (dynamicViews.isEmpty()) {
            notices.add(warning("extract.sequences_missing", "No dynamicViews for target softwareSystem", "/views/dynamicViews"));
            return;
        }

        int viewIdx = 0;
        for (JsonNode dynamicView : dynamicViews) {
            String pointer = "/views/dynamicViews/" + viewIdx;
            viewIdx++;

            String key = textOrNull(dynamicView, "key");
            if (key == null || key.isBlank()) {
                notices.add(warning("extract.sequence_key_missing", "dynamicView has no key", pointer));
                continue;
            }

            String description = textOrNull(dynamicView, "description");
            if (description == null) notices.add(info("extract.sequence_description_missing", "Sequence '" + key + "' has no description", pointer));
            String name = firstNonBlank(description, key);

            String tcUid = key.contains(".") ? key : cmdb + "." + key;
            if (!tcByUid.containsKey(tcUid)) {
                notices.add(warning("extract.sequence_tc_not_found", "TC for sequence key '" + key + "' not found", pointer));
                tcUid = null;
            }

            SequenceDraft sequence = new SequenceDraft();
            sequence.setUid(key);
            sequence.setExtUid(key);
            sequence.setName(name);
            sequence.setDescription(description);
            sequence.setTechCapabilityUid(tcUid);
            sequence.setContext(pointer);
            snapshot.getSequences().add(sequence);

            extractRelations(dynamicView, key, pointer, relationshipsById, operationUidByCanonicalKey, snapshot, notices);
        }
    }

    private void extractRelations(JsonNode dynamicView, String sequenceKey, String viewPointer,
                                   Map<String, RelationshipInfo> relationshipsById,
                                   Map<String, String> operationUidByCanonicalKey,
                                   StructurizrSequenceSnapshot snapshot, List<ArtifactNotice> notices) {
        List<JsonNode> steps = new ArrayList<>();
        dynamicView.path("relationships").forEach(steps::add);
        if (steps.isEmpty()) {
            notices.add(warning("extract.sequence_relations_missing", "dynamicView '" + sequenceKey + "' has no relationships", viewPointer));
            return;
        }
        steps.sort((a, b) -> Integer.compare(orderOf(a), orderOf(b)));

        String firstStepId = textOrNull(steps.get(0), "id");
        RelationshipInfo firstRelInfo = firstStepId != null ? relationshipsById.get(firstStepId) : null;
        String initiatorId = firstRelInfo != null ? firstRelInfo.sourceId() : null;

        Map<String, String> lastOperationUidByElementId = new HashMap<>();

        int relIdx = 0;
        for (JsonNode step : steps) {
            String stepPointer = viewPointer + "/relationships/" + relIdx;
            relIdx++;

            if (step.path("response").asBoolean(false)) continue;

            String stepId = textOrNull(step, "id");
            RelationshipInfo relInfo = stepId != null ? relationshipsById.get(stepId) : null;
            if (relInfo == null) {
                notices.add(warning("extract.sequence_relation_operation_not_found", "relationship id '" + stepId + "' not found in model", stepPointer));
                continue;
            }

            String rawDescription = firstNonBlank(textOrNull(step, "description"), relInfo.description());
            String canonicalKey = canonicalOperationKey(rawDescription);
            String calleeOperationUid = canonicalKey != null ? operationUidByCanonicalKey.get(canonicalKey) : null;
            if (calleeOperationUid == null) {
                notices.add(warning("extract.sequence_relation_operation_not_found",
                        "No operation matches relationship description '" + rawDescription + "'", stepPointer));
            }

            Integer order = orderOf(step);
            if (order <= 0) notices.add(warning("extract.sequence_relation_invalid_order", "relationship has no valid order", stepPointer));

            String callerId = relInfo.sourceId();
            String calleeId = relInfo.destinationId();

            if (initiatorId != null && initiatorId.equals(callerId)) {
                SequenceRelationDraft relation = new SequenceRelationDraft();
                relation.setSequenceUid(sequenceKey);
                relation.setOperationUid(calleeOperationUid);
                relation.setCallOrder(order);
                relation.setContext(stepPointer);
                snapshot.getSequenceRelations().add(relation);
            } else {
                String callerOperationUid = callerId != null ? lastOperationUidByElementId.get(callerId) : null;
                if (callerOperationUid == null) {
                    notices.add(warning("extract.operation_relation_caller_not_found", "Could not resolve caller operation for relationship", stepPointer));
                }
                if (calleeOperationUid != null) {
                    OperationRelationDraft relation = new OperationRelationDraft();
                    relation.setOperationUid(callerOperationUid);
                    relation.setRelatedOperationUid(calleeOperationUid);
                    relation.setCallOrder(order);
                    relation.setContext(stepPointer);
                    snapshot.getOperationRelations().add(relation);
                } else {
                    notices.add(warning("extract.operation_relation_callee_not_found", "Could not resolve callee operation for relationship", stepPointer));
                }
            }

            if (calleeId != null && calleeOperationUid != null) {
                lastOperationUidByElementId.put(calleeId, calleeOperationUid);
            }
        }
    }

    // ---------------------------------------------------------------- model indexing (for relationship caller/callee resolution)

    private void indexModel(JsonNode model, Map<String, RelationshipInfo> relationshipsById) {
        for (JsonNode person : model.path("people")) {
            indexElement(person, relationshipsById);
        }
        for (JsonNode system : model.path("softwareSystems")) {
            indexElement(system, relationshipsById);
            for (JsonNode container : system.path("containers")) {
                indexElement(container, relationshipsById);
                for (JsonNode component : container.path("components")) {
                    indexElement(component, relationshipsById);
                }
            }
        }
        for (JsonNode deploymentNode : model.path("deploymentNodes")) {
            indexDeploymentNode(deploymentNode, relationshipsById);
        }
    }

    private void indexDeploymentNode(JsonNode node, Map<String, RelationshipInfo> relationshipsById) {
        indexElement(node, relationshipsById);
        for (JsonNode child : node.path("children")) {
            indexDeploymentNode(child, relationshipsById);
        }
        for (JsonNode instance : node.path("containerInstances")) {
            indexElement(instance, relationshipsById);
        }
        for (JsonNode instance : node.path("softwareSystemInstances")) {
            indexElement(instance, relationshipsById);
        }
    }

    private void indexElement(JsonNode element, Map<String, RelationshipInfo> relationshipsById) {
        for (JsonNode relationship : element.path("relationships")) {
            String relId = textOrNull(relationship, "id");
            if (relId != null) {
                relationshipsById.put(relId, new RelationshipInfo(
                        textOrNull(relationship, "sourceId"),
                        textOrNull(relationship, "destinationId"),
                        textOrNull(relationship, "description")));
            }
        }
    }

    private static int orderOf(JsonNode step) {
        JsonNode order = step.path("order");
        if (order.isMissingNode() || order.isNull()) return 0;
        if (order.isNumber()) return order.asInt();
        try {
            return Integer.parseInt(order.asText());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- notices

    private ArtifactNotice error(String code, String message, String pointer) {
        return notice(code, "error", message, pointer);
    }

    private ArtifactNotice warning(String code, String message, String pointer) {
        return notice(code, "warning", message, pointer);
    }

    private ArtifactNotice info(String code, String message, String pointer) {
        return notice(code, "info", message, pointer);
    }

    private ArtifactNotice notice(String code, String level, String message, String pointer) {
        return new ArtifactNotice(null, null, code, level, "extract", null, null, null, null,
                message, toJson(Map.of("message", message)), pointer, null);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
