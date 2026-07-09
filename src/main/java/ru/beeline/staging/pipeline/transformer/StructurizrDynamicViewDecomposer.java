package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.InterfaceDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.OperationDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.SequenceDraft;
import ru.beeline.staging.pipeline.transformer.StructurizrSequenceSnapshot.SequenceRelationDraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a Structurizr workspace export's views.dynamicViews onto the canonical Tc/Sequence model, per
 * structurizr-sequence-extract.md: each dynamicView is a Sequence, its ordered relationships[] form a
 * linear call chain (Structurizr dynamicViews number steps 1..N — there's no nesting/recursion the way
 * Sparx e2e messages have), and response=true relationships are return messages, not calls.
 */
@Component
@RequiredArgsConstructor
public class StructurizrDynamicViewDecomposer {

    // Authoring convention (see "Сценарии использования" vision doc): relationship description is
    // multi-line — first line is a human-readable message, last line is the real endpoint that
    // becomes the operation's identity ("GET /index.html"), and an optional middle line names the
    // TC of the external system being called ("BC-012345"). A single-line description (no \n at
    // all) is treated as the endpoint itself — some authors skip the descriptive first line.
    private static final Pattern HTTP_METHOD_PATTERN = Pattern.compile("^(GET|POST|PUT|DELETE|PATCH|OPTIONS|HEAD)\\b", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    public record Result(StructurizrSequenceSnapshot snapshot, List<ArtifactNotice> notices) {}

    private record ElementInfo(String code, String name, String type) {}
    private record RelationshipInfo(String sourceId, String destinationId, String description, String technology) {}
    private record RelationshipText(String message, String tcCode, String endpoint) {}

    public Result decompose(JsonNode root, String artifactUid) {
        List<ArtifactNotice> notices = new ArrayList<>();
        StructurizrSequenceSnapshot snapshot = new StructurizrSequenceSnapshot();

        StructurizrSequenceSnapshot.TcDraft tc = new StructurizrSequenceSnapshot.TcDraft();
        tc.setTcCode(artifactUid);
        tc.setName(artifactUid);
        snapshot.setTc(tc);

        JsonNode model = root.path("model");
        Map<String, ElementInfo> elementsById = new LinkedHashMap<>();
        Map<String, RelationshipInfo> relationshipsById = new LinkedHashMap<>();
        indexModel(model, elementsById, relationshipsById);

        Map<String, OperationDraft> operationDrafts = new LinkedHashMap<>();
        Set<String> registeredInterfaces = new LinkedHashSet<>();

        JsonNode dynamicViews = root.path("views").path("dynamicViews");
        int viewIdx = 0;
        for (JsonNode dynamicView : dynamicViews) {
            String pointer = "/views/dynamicViews/" + viewIdx;
            viewIdx++;

            String key = textOrNull(dynamicView, "key");
            if (key == null || key.isBlank()) {
                // Per-item skip, not a whole-artifact failure — same reasoning as the validator's
                // downgrade of this exact case (see StructurizrSequenceValidator).
                notices.add(mapFailed("warning", details("missing_required_field", "field", "key"), pointer));
                continue;
            }

            String title = firstNonBlank(textOrNull(dynamicView, "title"), textOrNull(dynamicView, "description"), key);
            String description = textOrNull(dynamicView, "description");

            SequenceDraft sequence = new SequenceDraft();
            sequence.setKey(key);
            sequence.setTcCode(artifactUid);
            sequence.setName(title);
            sequence.setDescription(description);
            sequence.setContext(pointer);
            snapshot.getSequences().add(sequence);

            List<JsonNode> steps = new ArrayList<>();
            dynamicView.path("relationships").forEach(steps::add);
            steps.sort((a, b) -> Integer.compare(orderOf(a), orderOf(b)));

            List<String> chainedOperationExtUids = new ArrayList<>();
            int relIdx = 0;
            for (JsonNode step : steps) {
                String stepPointer = pointer + "/relationships/" + relIdx;
                relIdx++;

                String relationshipId = textOrNull(step, "id");
                boolean isResponse = step.path("response").asBoolean(false);
                if (isResponse) {
                    notices.add(dataLoss("response_message", relationshipId, stepPointer));
                    continue;
                }

                RelationshipInfo relInfo = relationshipId != null ? relationshipsById.get(relationshipId) : null;
                if (relInfo == null) {
                    notices.add(mapFailed("warning", details("missing_reference", "field", "relationships[].id",
                            "value", String.valueOf(relationshipId)), stepPointer));
                    continue;
                }

                String rawDescription = firstNonBlank(textOrNull(step, "description"), relInfo.description());
                RelationshipText text = parseRelationshipText(rawDescription);
                String operationName = firstNonBlank(text.endpoint(), relationshipId);
                registerOperationAndInterface(relationshipId, operationName, relInfo, elementsById,
                        operationDrafts, registeredInterfaces, snapshot, stepPointer);
                chainedOperationExtUids.add(relationshipId);
            }

            for (int i = 1; i < chainedOperationExtUids.size(); i++) {
                SequenceRelationDraft rel = new SequenceRelationDraft();
                rel.setSequenceKey(key);
                rel.setCallerOperationExtUid(chainedOperationExtUids.get(i - 1));
                rel.setCalleeOperationExtUid(chainedOperationExtUids.get(i));
                rel.setCallOrder(i);
                rel.setContext(pointer);
                snapshot.getSequenceRelations().add(rel);
            }
        }

        return new Result(snapshot, notices);
    }

    private void registerOperationAndInterface(String relationshipId, String name, RelationshipInfo relInfo,
                                                Map<String, ElementInfo> elementsById,
                                                Map<String, OperationDraft> operationDrafts,
                                                Set<String> registeredInterfaces,
                                                StructurizrSequenceSnapshot snapshot, String pointer) {
        if (operationDrafts.containsKey(relationshipId)) return;

        ElementInfo callee = elementsById.get(relInfo.destinationId());
        String interfaceUid = callee != null ? callee.code() : relInfo.destinationId();

        OperationDraft operation = new OperationDraft();
        operation.setExtUid(relationshipId);
        operation.setName(name);
        operation.setType(httpMethodOf(name));
        operation.setInterfaceUid(interfaceUid);
        operation.setContext(pointer);
        operationDrafts.put(relationshipId, operation);
        snapshot.getOperations().add(operation);

        if (interfaceUid != null && registeredInterfaces.add(interfaceUid)) {
            InterfaceDraft iface = new InterfaceDraft();
            iface.setUid(interfaceUid);
            iface.setExtUid(relInfo.destinationId());
            iface.setProtocol(relInfo.technology());
            iface.setSource("structurizr");
            iface.setContext(pointer);
            snapshot.getInterfaces().add(iface);
        }
    }

    // ------------------------------------------------------------------
    // model.* indexing — people/softwareSystems(+containers+components)/deploymentNodes, recursively
    // ------------------------------------------------------------------

    private void indexModel(JsonNode model, Map<String, ElementInfo> elementsById, Map<String, RelationshipInfo> relationshipsById) {
        for (JsonNode person : model.path("people")) {
            indexElement(person, "person", elementsById, relationshipsById);
        }
        for (JsonNode system : model.path("softwareSystems")) {
            indexElement(system, "softwareSystem", elementsById, relationshipsById);
            for (JsonNode container : system.path("containers")) {
                indexElement(container, "container", elementsById, relationshipsById);
                for (JsonNode component : container.path("components")) {
                    indexElement(component, "component", elementsById, relationshipsById);
                }
            }
        }
        for (JsonNode deploymentNode : model.path("deploymentNodes")) {
            indexDeploymentNode(deploymentNode, elementsById, relationshipsById);
        }
    }

    private void indexDeploymentNode(JsonNode node, Map<String, ElementInfo> elementsById, Map<String, RelationshipInfo> relationshipsById) {
        indexElement(node, "deploymentNode", elementsById, relationshipsById);
        for (JsonNode child : node.path("children")) {
            indexDeploymentNode(child, elementsById, relationshipsById);
        }
        for (JsonNode instance : node.path("containerInstances")) {
            indexElement(instance, "containerInstance", elementsById, relationshipsById);
        }
        for (JsonNode instance : node.path("softwareSystemInstances")) {
            indexElement(instance, "softwareSystemInstance", elementsById, relationshipsById);
        }
    }

    private void indexElement(JsonNode element, String type, Map<String, ElementInfo> elementsById, Map<String, RelationshipInfo> relationshipsById) {
        String id = textOrNull(element, "id");
        if (id != null) {
            String code = firstNonBlank(textOrNull(element.path("properties"), "structurizr.dsl.identifier"),
                    textOrNull(element, "name"), id);
            elementsById.put(id, new ElementInfo(code, textOrNull(element, "name"), type));
        }
        for (JsonNode relationship : element.path("relationships")) {
            String relId = textOrNull(relationship, "id");
            if (relId != null) {
                relationshipsById.put(relId, new RelationshipInfo(
                        textOrNull(relationship, "sourceId"),
                        textOrNull(relationship, "destinationId"),
                        textOrNull(relationship, "description"),
                        textOrNull(relationship, "technology")));
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ArtifactNotice mapFailed(String level, Map<String, Object> details, String pointer) {
        return notice("structurizr-sequence.transform.map_failed", level, details, pointer);
    }

    private ArtifactNotice dataLoss(String reason, String relationshipId, String pointer) {
        return notice("structurizr-sequence.transform.data_loss", "info", details(reason, "relationship_id", String.valueOf(relationshipId)), pointer);
    }

    private ArtifactNotice notice(String code, String level, Map<String, Object> details, String pointer) {
        return new ArtifactNotice(null, null, code, level, "transform", null, null, null, null, code, toJson(details), pointer, null);
    }

    private Map<String, Object> details(String reason, Object... kv) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", reason);
        for (int i = 0; i < kv.length; i += 2) d.put(String.valueOf(kv[i]), kv[i + 1]);
        return d;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
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

    private static RelationshipText parseRelationshipText(String description) {
        if (description == null || description.isBlank()) {
            return new RelationshipText(null, null, null);
        }
        String[] lines = description.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) lines[i] = lines[i].trim();

        if (lines.length == 1) {
            // No separate message line — treat the single line as the endpoint itself.
            return new RelationshipText(null, null, lines[0]);
        }
        String message  = lines[0];
        String endpoint = lines[lines.length - 1];
        String tcCode   = lines.length >= 3
                ? String.join(" ", Arrays.copyOfRange(lines, 1, lines.length - 1))
                : null;
        return new RelationshipText(message, tcCode, endpoint);
    }

    private static String httpMethodOf(String endpoint) {
        if (endpoint == null) return null;
        Matcher m = HTTP_METHOD_PATTERN.matcher(endpoint.trim());
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
