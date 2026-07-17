package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.BiStepDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.InterfaceDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.OperationDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.OperationRelationDraft;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the call tree from a raw Sparx EA scenario export and filters internal calls, mirroring
 * dashboard-service's collapsing rules (src/api/services/scenarios-service/build-call-tree.mjs).
 * Every fragment that gets filtered out or fails to map is recorded as a transform.* ArtifactNotice
 * instead of silently disappearing.
 */
@Component
@RequiredArgsConstructor
public class ScenarioDecomposer {

    private static final Set<String> EXCLUDED_NAMES = Set.of("use", "use()");
    private static final Pattern STEP_ID_PATTERN = Pattern.compile("step_id=([A-Za-z0-9._-]+)");

    private final ObjectMapper objectMapper;

    public record Result(E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices, String stepId) {}

    public Result decompose(JsonNode root, String artifactUid) {
        List<ArtifactNotice> notices = new ArrayList<>();
        E2ESequenceSnapshot snapshot = new E2ESequenceSnapshot();

        String entranceDiagramUid = textOrNull(root, "entrance_diagram_uid");
        Map<String, JsonNode> diagramsByUid = indexByStringField(root.path("diagrams"), "uid");
        Map<Integer, JsonNode> objectsById = indexByIntField(root.path("objects"), "id");
        Map<Integer, JsonNode> systemsById = indexByIntField(root.path("systems"), "id");
        Map<Integer, JsonNode> interfacesById = indexByIntField(root.path("interfaces"), "id");
        Map<String, JsonNode> operationsByUid = indexByStringField(root.path("operations"), "uid");
        // RFC6901 pointers into root.diagrams[]/interfaces[]/operations[] by array position — stored
        // as the json_path in raw_data_context for bi_step/interface/operation drafts and notices.
        Map<String, Integer> diagramArrayIndexByUid = arrayIndexByStringField(root.path("diagrams"), "uid");
        Map<Integer, Integer> interfaceArrayIndexById = arrayIndexByIntField(root.path("interfaces"), "id");
        Map<String, Integer> operationArrayIndexByUid = arrayIndexByStringField(root.path("operations"), "uid");

        JsonNode rootDiagram = entranceDiagramUid != null ? diagramsByUid.get(entranceDiagramUid) : null;
        if (rootDiagram == null) {
            notices.add(mapFailed("error", details("missing_reference", "field", "entrance_diagram_uid",
                    "value", String.valueOf(entranceDiagramUid)), "/entrance_diagram_uid"));
            return new Result(snapshot, notices, null);
        }
        Integer rootDiagramIdx = diagramArrayIndexByUid.get(entranceDiagramUid);
        String rootDiagramPointer = rootDiagramIdx != null ? "/diagrams/" + rootDiagramIdx : "/entrance_diagram_uid";

        String stepId = parseStepId(textOrNull(rootDiagram, "notes"));
        if (stepId == null) {
            notices.add(mapFailed("warning", details("missing_required_field", "field", "step_id",
                    "diagram_uid", entranceDiagramUid), rootDiagramPointer + "/notes"));
        }

        // Step 3: per-diagram local call trees (seqno order, is_ret/use/self-call dropped)
        Map<String, CallNode> diagramRoots = new LinkedHashMap<>();
        for (JsonNode diagram : root.path("diagrams")) {
            String diagramUid = textOrNull(diagram, "uid");
            if (diagramUid == null) continue;
            Integer diagramIdx = diagramArrayIndexByUid.get(diagramUid);
            diagramRoots.put(diagramUid, buildLocalTree(diagram, diagramUid, diagramIdx, objectsById, systemsById, interfacesById, operationsByUid, notices));
        }

        // Step 4: merge child diagrams via linked_diagram_uid — one flat pass over every node
        List<CallNode> allNodes = new ArrayList<>();
        for (CallNode dr : diagramRoots.values()) collectAll(dr, allNodes);
        for (CallNode node : allNodes) {
            linkChildDiagram(node, diagramRoots, notices);
        }

        // Step 5: collapse internal calls, starting from the root diagram wrapped in an app_front=1 context
        CallNode rootWrapper = new CallNode();
        rootWrapper.appFront = true;
        rootWrapper.children = diagramRoots.get(entranceDiagramUid).children;
        List<CallNode> finalSequence = collapse(rootWrapper, notices);

        // Step 6/7: decompose the surviving tree into canonical drafts
        // bi_steps.uid/ext_uid is the business key (step_id, e.g. "Step.01.00.00.00"); the Sparx
        // diagram GUID (entrance_diagram_uid) is now the e2e_scenario's own identity, linked to the
        // bi_step via e2e_scenario_versions.bi_step_version_id instead of the other way around.
        if (stepId != null) {
            BiStepDraft biStep = new BiStepDraft();
            biStep.setUid(stepId);
            biStep.setName(textOrNull(rootDiagram, "name"));
            biStep.setExtUid(stepId);
            biStep.setContext(rootDiagramPointer);
            snapshot.getBiSteps().add(biStep);
        }

        E2ESequenceSnapshot.E2eScenarioDraft scenario = new E2ESequenceSnapshot.E2eScenarioDraft();
        scenario.setUid(entranceDiagramUid);
        scenario.setExtUid(entranceDiagramUid);
        scenario.setName(textOrNull(rootDiagram, "name"));
        scenario.setContext(rootDiagramPointer);
        if (stepId != null) {
            scenario.setBiStepUid(stepId);
            scenario.setDescription("step_id=" + stepId);
            notices.add(notice("transform.implicit_cast", "info",
                    Map.of("field", "description", "action", "constructed", "value", "step_id=" + stepId,
                            "source", "diagrams[root].notes -> step_id"),
                    rootDiagramPointer));
        }
        snapshot.setE2eScenario(scenario);

        Map<String, OperationDraft> operationDrafts = new LinkedHashMap<>();
        Set<String> registeredInterfaces = new LinkedHashSet<>();
        int callOrder = 0;
        for (CallNode node : finalSequence) {
            if (node.operationGuid == null) {
                notices.add(mapFailed("warning", details("unresolved_after_filtering", "message_uid", node.uid,
                        "message_name", node.name), node.pointer));
                continue;
            }
            registerOperationAndInterface(node, operationsByUid, interfacesById, operationArrayIndexByUid,
                    interfaceArrayIndexById, operationDrafts, registeredInterfaces, snapshot, notices);

            // Root-level call: no caller operation (operation_version_id = NULL in operation_relation_versions).
            OperationRelationDraft rel = new OperationRelationDraft();
            rel.setCallerOperationExtUid(null);
            rel.setCalleeOperationExtUid(node.operationGuid);
            rel.setCallOrder(callOrder++);
            rel.setStereotype(node.stereotype);
            rel.setContext(node.pointer);
            snapshot.getOperationRelations().add(rel);

            decomposeChildren(node, operationsByUid, interfacesById, operationArrayIndexByUid,
                    interfaceArrayIndexById, operationDrafts, registeredInterfaces, snapshot, notices);
        }

        return new Result(snapshot, notices, stepId);
    }

    // ------------------------------------------------------------------
    // Step 3 — per-diagram local tree (mirrors build-call-tree.mjs addMessage)
    // ------------------------------------------------------------------

    private CallNode buildLocalTree(JsonNode diagram, String diagramUid, Integer diagramIdx, Map<Integer, JsonNode> objectsById,
                                     Map<Integer, JsonNode> systemsById, Map<Integer, JsonNode> interfacesById, Map<String, JsonNode> operationsByUid,
                                     List<ArtifactNotice> notices) {
        CallNode root = new CallNode();
        root.diagramUid = diagramUid;
        root.serverId = 0;

        List<JsonNode> messages = new ArrayList<>();
        diagram.path("messages").forEach(messages::add);

        Set<String> seenMessageUids = new HashSet<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            String msgUid = textOrNull(messages.get(i), "uid");
            if (msgUid != null && !seenMessageUids.add(msgUid)) {
                String pointer = diagramIdx != null ? "/diagrams/" + diagramIdx + "/messages/" + i : "/diagrams/messages/" + i;
                notices.add(notice("transform.data_loss", "info",
                        details("duplicate_message_row", "message_uid", msgUid), pointer));
                continue;
            }
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> intOrZero(messages.get(i), "seqno")));

        CallNode context = root;
        for (int originalIdx : order) {
            JsonNode msg = messages.get(originalIdx);
            CallNode node = wrapMessage(msg, diagramUid, diagramIdx, originalIdx, objectsById, systemsById, interfacesById, operationsByUid, notices);

            if (node.isRet) { noteSequenceSkip(node, "is_ret", notices); continue; }
            if (node.name != null && EXCLUDED_NAMES.contains(node.name.trim().toLowerCase())) {
                noteSequenceSkip(node, "use_message", notices);
                continue;
            }
            if (Objects.equals(node.serverId, node.clientId)) { noteSequenceSkip(node, "self_call", notices); continue; }

            CallNode ctx = context;
            if (ctx.serverId != null && ctx.serverId == 0 || Objects.equals(node.clientId, ctx.serverId)) {
                ctx.children.add(node);
                node.parentContext = ctx;
            } else {
                while (ctx.parentContext != null && !Objects.equals(ctx.serverId, node.clientId)) {
                    ctx = ctx.parentContext;
                }
                ctx.children.add(node);
                node.parentContext = ctx;
            }
            context = node;
        }
        return root;
    }

    private CallNode wrapMessage(JsonNode msg, String diagramUid, Integer diagramIdx, int originalIdx, Map<Integer, JsonNode> objectsById,
                                  Map<Integer, JsonNode> systemsById, Map<Integer, JsonNode> interfacesById, Map<String, JsonNode> operationsByUid,
                                  List<ArtifactNotice> notices) {
        CallNode node = new CallNode();
        node.diagramUid = diagramUid;
        node.pointer = diagramIdx != null ? "/diagrams/" + diagramIdx + "/messages/" + originalIdx
                                           : "/diagrams/messages/" + originalIdx;
        node.uid = textOrNull(msg, "uid");
        node.name = textOrNull(msg, "name");
        node.clientId = intOrNull(msg, "start_object_id");
        node.serverId = intOrNull(msg, "end_object_id");
        node.stereotype = textOrNull(msg, "stereotype");
        node.operationGuid = textOrNull(msg, "operation_guid");
        node.linkedDiagramUid = textOrNull(msg, "linked_diagram_uid");
        node.isRet = "1".equals(textOrNull(msg, "pdata4"));

        if (node.clientId != null && !objectsById.containsKey(node.clientId)) {
            notices.add(mapFailed("warning", details("missing_reference", "field", "start_object_id",
                    "value", String.valueOf(node.clientId), "message_uid", node.uid), node.pointer));
        }

        JsonNode serverObj = node.serverId != null ? objectsById.get(node.serverId) : null;
        node.serverAppCode = resolveAppCode(serverObj, systemsById);
        if (node.serverId != null && serverObj == null) {
            notices.add(mapFailed("warning", details("missing_reference", "field", "end_object_id",
                    "value", String.valueOf(node.serverId), "message_uid", node.uid), node.pointer));
        }

        if (node.operationGuid != null) {
            JsonNode op = operationsByUid.get(node.operationGuid);
            if (op == null) {
                notices.add(mapFailed("warning", details("missing_reference", "field", "operation_guid",
                        "value", node.operationGuid, "message_uid", node.uid), node.pointer));
            } else {
                node.methodResolved = true;
                Integer ifaceId = intOrNull(op, "interface_id");
                JsonNode iface = ifaceId != null ? interfacesById.get(ifaceId) : null;
                if (iface == null) {
                    notices.add(mapFailed("warning", details("missing_reference", "field", "interface_id",
                            "value", String.valueOf(ifaceId), "operation_uid", node.operationGuid), node.pointer));
                } else {
                    Map<String, String> tags = tagsOf(iface);
                    node.methodAppFront = "1".equals(tags.get("app_front"));
                    node.methodShowInE2e = "1".equals(tags.get("show_in_e2e"));
                }
            }
        }
        return node;
    }

    private void noteSequenceSkip(CallNode node, String reason, List<ArtifactNotice> notices) {
        notices.add(notice("transform.data_loss", "info",
                details(reason, "message_uid", node.uid, "message_name", node.name), node.pointer));
    }

    // ------------------------------------------------------------------
    // Step 4 — child diagram merge (mirrors build-call-tree.mjs Pass 2/3)
    // ------------------------------------------------------------------

    private void collectAll(CallNode node, List<CallNode> out) {
        out.add(node);
        for (CallNode ch : node.children) collectAll(ch, out);
    }

    private void linkChildDiagram(CallNode node, Map<String, CallNode> diagramRoots, List<ArtifactNotice> notices) {
        if (node.linkedDiagramUid == null || node.linkedDiagramUid.equals(node.diagramUid)) return;

        String operationGuid = node.operationGuid;
        CallNode ancestor = node;
        while (operationGuid == null && ancestor.parentContext != null) {
            ancestor = ancestor.parentContext;
            operationGuid = ancestor.operationGuid;
        }
        if (operationGuid == null) {
            notices.add(mapFailed("warning", details("no_operation_guid_in_call_chain",
                    "message_uid", node.uid, "linked_diagram_uid", node.linkedDiagramUid), node.pointer));
            return;
        }

        CallNode targetRoot = diagramRoots.get(node.linkedDiagramUid);
        if (targetRoot == null) {
            notices.add(mapFailed("warning", details("linked_diagram_not_found",
                    "linked_diagram_uid", node.linkedDiagramUid, "message_uid", node.uid), node.pointer));
            return;
        }

        List<CallNode> matches = new ArrayList<>();
        for (CallNode entry : targetRoot.children) {
            if (Objects.equals(entry.operationGuid, operationGuid)) matches.add(entry);
        }
        if (matches.isEmpty()) {
            notices.add(mapFailed("warning", details("entry_point_not_found", "operation_guid", operationGuid,
                    "linked_diagram_uid", node.linkedDiagramUid), node.pointer));
            return;
        }
        if (matches.size() > 1) {
            notices.add(mapFailed("warning", details("ambiguous_entry_point", "operation_guid", operationGuid,
                    "linked_diagram_uid", node.linkedDiagramUid, "matchCount", String.valueOf(matches.size())), node.pointer));
        }
        for (CallNode m : matches) {
            node.children.addAll(m.children);
        }
    }

    // ------------------------------------------------------------------
    // Step 5 — collapse internal calls (mirrors build-call-tree.mjs removeInternalMessages, 4 rules)
    // ------------------------------------------------------------------

    private List<CallNode> collapse(CallNode parent, List<ArtifactNotice> notices) {
        List<CallNode> result = new ArrayList<>();
        for (CallNode ch : parent.children) {
            if (parent.appFront && !ch.methodResolved) {
                ch.appFront = true;
                skip(parent, ch, result, "app_front_no_method", notices);
                continue;
            }
            if (ch.methodAppFront) {
                skip(parent, ch, result, "app_front", notices);
                continue;
            }
            if (ch.methodShowInE2e && !Objects.equals(ch.operationGuid, parent.operationGuid)) {
                keep(ch, result, notices, "show_in_e2e_override");
                continue;
            }
            boolean sameAppCode = ch.serverAppCode != null && ch.serverAppCode.equals(parent.serverAppCode);
            boolean sameOperationGuid = ch.operationGuid != null && ch.operationGuid.equals(parent.operationGuid);
            boolean noAppCode = ch.serverAppCode == null;
            if (sameAppCode || sameOperationGuid || noAppCode) {
                skip(parent, ch, result, sameAppCode ? "same_system" : sameOperationGuid ? "self_reference" : "unresolved_system", notices);
            } else {
                keep(ch, result, notices, "external_call");
            }
        }
        return result;
    }

    private void skip(CallNode parent, CallNode ch, List<CallNode> result, String reason, List<ArtifactNotice> notices) {
        notices.add(notice("transform.data_loss", "warning",
                details("internal_call", "type", reason, "message_uid", ch.uid, "message_name", ch.name,
                        "caller", parent.serverAppCode, "callee", ch.serverAppCode),
                ch.pointer));

        boolean rewriteIdentity = (ch.serverAppCode != null && ch.serverAppCode.equals(parent.serverAppCode))
                || (ch.operationGuid != null && ch.operationGuid.equals(parent.operationGuid))
                || ch.serverAppCode == null;
        if (rewriteIdentity) {
            ch.operationGuid = parent.operationGuid;
            ch.serverAppCode = parent.serverAppCode;
            ch.serverId = parent.serverId;
        }
        result.addAll(collapse(ch, notices));
    }

    private void keep(CallNode ch, List<CallNode> result, List<ArtifactNotice> notices, String reason) {
        notices.add(notice("transform.included", "info",
                details(reason, "message_uid", ch.uid, "message_name", ch.name,
                        "operation_guid", ch.operationGuid, "callee", ch.serverAppCode),
                ch.pointer));
        ch.children = collapse(ch, notices);
        result.add(ch);
    }

    // ------------------------------------------------------------------
    // Step 6/7 — decompose the surviving tree into canonical drafts
    // ------------------------------------------------------------------

    private void decomposeChildren(CallNode parent, Map<String, JsonNode> operationsByUid, Map<Integer, JsonNode> interfacesById,
                                    Map<String, Integer> operationArrayIndexByUid, Map<Integer, Integer> interfaceArrayIndexById,
                                    Map<String, OperationDraft> operationDrafts, Set<String> registeredInterfaces,
                                    E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices) {
        int callOrder = 0;
        for (CallNode child : parent.children) {
            if (child.operationGuid == null) {
                notices.add(mapFailed("warning", details("unresolved_after_filtering", "message_uid", child.uid,
                        "message_name", child.name), child.pointer));
                continue;
            }
            registerOperationAndInterface(child, operationsByUid, interfacesById, operationArrayIndexByUid,
                    interfaceArrayIndexById, operationDrafts, registeredInterfaces, snapshot, notices);

            OperationRelationDraft rel = new OperationRelationDraft();
            rel.setCallerOperationExtUid(parent.operationGuid);
            rel.setCalleeOperationExtUid(child.operationGuid);
            rel.setCallOrder(callOrder++);
            rel.setStereotype(child.stereotype);
            rel.setContext(child.pointer);
            snapshot.getOperationRelations().add(rel);

            decomposeChildren(child, operationsByUid, interfacesById, operationArrayIndexByUid,
                    interfaceArrayIndexById, operationDrafts, registeredInterfaces, snapshot, notices);
        }
    }

    private void registerOperationAndInterface(CallNode node, Map<String, JsonNode> operationsByUid, Map<Integer, JsonNode> interfacesById,
                                                Map<String, Integer> operationArrayIndexByUid, Map<Integer, Integer> interfaceArrayIndexById,
                                                Map<String, OperationDraft> operationDrafts, Set<String> registeredInterfaces,
                                                E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices) {
        if (operationDrafts.containsKey(node.operationGuid)) return;

        JsonNode op = operationsByUid.get(node.operationGuid);
        OperationDraft draft = new OperationDraft();
        draft.setExtUid(node.operationGuid);
        draft.setName(op != null ? textOrNull(op, "name") : node.name);
        // Points at root.operations[N] itself, not the calling message.
        Integer opIdx = operationArrayIndexByUid.get(node.operationGuid);
        draft.setContext(opIdx != null ? "/operations/" + opIdx : node.pointer);

        Map<String, String> tags = op != null ? tagsOf(op) : Map.of();
        Double rps = numOrNull(tags.get("rps"));
        Double latency = numOrNull(tags.get("latency"));
        Double errorRate = numOrNull(tags.get("error_rate"));
        draft.setRps(rps);
        draft.setLatency(latency);
        draft.setErrorRate(errorRate);
        if (!tags.isEmpty()) {
            notices.add(implicitCastNotice(node, tags, rps, latency, errorRate));
        }

        Integer ifaceId = op != null ? intOrNull(op, "interface_id") : null;
        JsonNode iface = ifaceId != null ? interfacesById.get(ifaceId) : null;
        if (iface != null) {
            String ifaceUid = textOrNull(iface, "code");
            draft.setInterfaceUid(ifaceUid);
            if (ifaceUid != null && registeredInterfaces.add(ifaceUid)) {
                InterfaceDraft ifaceDraft = new InterfaceDraft();
                ifaceDraft.setUid(ifaceUid);
                ifaceDraft.setExtUid(String.valueOf(ifaceId));
                ifaceDraft.setProtocol(tagsOf(iface).get("protocol"));
                ifaceDraft.setSource(textOrNull(iface, "source"));
                Integer ifaceIdx = interfaceArrayIndexById.get(ifaceId);
                ifaceDraft.setContext(ifaceIdx != null ? "/interfaces/" + ifaceIdx : null);
                snapshot.getInterfaces().add(ifaceDraft);
            }
        }
        operationDrafts.put(node.operationGuid, draft);
        snapshot.getOperations().add(draft);
    }

    private ArtifactNotice implicitCastNotice(CallNode node, Map<String, String> tags, Double rps, Double latency, Double errorRate) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("operation_guid", node.operationGuid);
        if (tags.get("rps") != null)        d.put("rps",        Map.of("source_value", tags.get("rps"), "target_value", String.valueOf(rps)));
        if (tags.get("latency") != null)    d.put("latency",    Map.of("source_value", tags.get("latency"), "target_value", String.valueOf(latency)));
        if (tags.get("error_rate") != null) d.put("error_rate", Map.of("source_value", tags.get("error_rate"), "target_value", String.valueOf(errorRate)));
        return notice("transform.implicit_cast", "info", d, node.pointer);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String parseStepId(String notes) {
        if (notes == null) return null;
        Matcher m = STEP_ID_PATTERN.matcher(notes);
        return m.find() ? m.group(1) : null;
    }

    private ArtifactNotice mapFailed(String level, Map<String, Object> details, String pointer) {
        return notice("transform.map_failed", level, details, pointer);
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

    private Map<String, JsonNode> indexByStringField(JsonNode array, String field) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode item : array) {
            String key = textOrNull(item, field);
            if (key != null) map.put(key, item);
        }
        return map;
    }

    private Map<Integer, JsonNode> indexByIntField(JsonNode array, String field) {
        Map<Integer, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode item : array) {
            Integer key = intOrNull(item, field);
            if (key != null) map.put(key, item);
        }
        return map;
    }

    private Map<Integer, Integer> arrayIndexByIntField(JsonNode array, String field) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        int idx = 0;
        for (JsonNode item : array) {
            Integer key = intOrNull(item, field);
            if (key != null) map.put(key, idx);
            idx++;
        }
        return map;
    }

    private Map<String, Integer> arrayIndexByStringField(JsonNode array, String field) {
        Map<String, Integer> map = new LinkedHashMap<>();
        int idx = 0;
        for (JsonNode item : array) {
            String key = textOrNull(item, field);
            if (key != null) map.put(key, idx);
            idx++;
        }
        return map;
    }

    private Map<String, String> tagsOf(JsonNode entity) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : entity.path("tags")) {
            String property = textOrNull(tag, "property");
            String value = textOrNull(tag, "value");
            if (property != null) tags.put(property, value);
        }
        return tags;
    }

    private static String resolveAppCode(JsonNode obj, Map<Integer, JsonNode> systemsById) {
        if (obj == null) return null;
        Integer systemId = intOrNull(obj, "system_id");
        JsonNode system = systemId != null ? systemsById.get(systemId) : null;
        String code = system != null ? textOrNull(system, "code") : null;
        return code != null ? code : textOrNull(obj, "alias");
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private static int intOrZero(JsonNode node, String field) {
        Integer v = intOrNull(node, field);
        return v != null ? v : 0;
    }

    private static Double numOrNull(String value) {
        if (value == null) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static class CallNode {
        String diagramUid;
        String pointer;
        String uid;
        String name;
        Integer clientId;
        Integer serverId;
        String serverAppCode;
        String operationGuid;
        String stereotype;
        String linkedDiagramUid;
        boolean isRet;
        boolean methodResolved;
        boolean methodAppFront;
        boolean methodShowInE2e;
        boolean appFront;
        CallNode parentContext;
        List<CallNode> children = new ArrayList<>();
    }
}
