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
 * Builds the call tree from a raw Sparx EA scenario export and filters internal
 * calls, mirroring
 * dashboard-service's collapsing rules
 * (src/api/services/scenarios-service/build-call-tree.mjs).
 * Every fragment that gets filtered out or fails to map is recorded as a
 * transform.* ArtifactNotice
 * instead of silently disappearing.
 */
@Component
@RequiredArgsConstructor
public class ScenarioDecomposer {

    private static final Set<String> EXCLUDED_NAMES = Set.of("use", "use()");
    private static final Pattern STEP_ID_PATTERN = Pattern.compile("step_id=([A-Za-z0-9._-]+)");

    private final ObjectMapper objectMapper;

    public record Result(E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices, String stepId) {
    }

    public Result decompose(JsonNode root, String artifactUid) {
        List<ArtifactNotice> notices = new ArrayList<>();
        E2ESequenceSnapshot snapshot = new E2ESequenceSnapshot();

        String entranceDiagramUid = textOrNull(root, "entrance_diagram_uid");
        Map<String, JsonNode> diagramsByUid = indexByStringField(root.path("diagrams"), "uid");
        Map<Integer, JsonNode> objectsById = indexByIntField(root.path("objects"), "id");
        Map<Integer, JsonNode> systemsById = indexByIntField(root.path("systems"), "id");
        Map<Integer, JsonNode> interfacesById = indexByIntField(root.path("interfaces"), "id");
        Map<Integer, JsonNode> containersById = indexByIntField(root.path("containers"), "id");
        Map<String, JsonNode> operationsByUid = indexByStringField(root.path("operations"), "uid");
        // RFC6901 pointers into root.diagrams[]/interfaces[]/operations[] by array
        // position — stored
        // as the json_path in raw_data_context for bi_step/interface/operation drafts
        // and notices.
        Map<String, Integer> diagramArrayIndexByUid = arrayIndexByStringField(root.path("diagrams"), "uid");
        Map<Integer, Integer> interfaceArrayIndexById = arrayIndexByIntField(root.path("interfaces"), "id");
        Map<String, Integer> operationArrayIndexByUid = arrayIndexByStringField(root.path("operations"), "uid");

        // Product (systems[] -> products/product_versions, per transform-spec §4.2)
        Set<String> seenProductCodes = new HashSet<>();
        int systemIdx = 0;
        for (JsonNode system : root.path("systems")) {
            String pointer = "/systems/" + systemIdx;
            systemIdx++;
            String code = textOrNull(system, "code");
            if (code == null || code.isBlank()) {
                notices.add(mapFailed("error", details("missing_required_field", "field", "code",
                        "system_id", String.valueOf(intOrNull(system, "id"))), pointer));
                continue;
            }
            if (!seenProductCodes.add(code)) {
                notices.add(duplicateKeyNotice("uid", code, "systems", systemIdx - 1, pointer));
                continue;
            }
            E2ESequenceSnapshot.ProductDraft product = new E2ESequenceSnapshot.ProductDraft();
            product.setUid(code);
            product.setExtUid(code);
            product.setName(textOrNull(system, "name"));
            product.setContext(pointer);
            notices.add(notice("transform.include", "info", details("product_found", "product_code", code), pointer));
            snapshot.getProducts().add(product);
        }

        // Container (containers[] -> containers/container_versions, per transform-spec
        // §4.2.1: uid is
        // containers[].code with the trailing ".<cmdb>" suffix stripped,
        // case-insensitively, where cmdb
        // is the owning system's code — denormalized onto the container row as
        // system_code, since
        // containers[].system_id doesn't reliably resolve against systems[] (systems[]
        // is scoped to
        // systems that appear as diagram objects; a container's owning system may never
        // appear on the
        // diagram itself even though its interface does). cleanedContainerCodeById is
        // reused below when
        // resolving an interface's owning container (§4.2.2), so both places agree on
        // the same uid.
        Set<String> seenContainerCodes = new HashSet<>();
        Map<Integer, String> cleanedContainerCodeById = new LinkedHashMap<>();
        int containerIdx = 0;
        for (JsonNode container : root.path("containers")) {
            String pointer = "/containers/" + containerIdx;
            Integer containerId = intOrNull(container, "id");
            containerIdx++;
            String rawCode = textOrNull(container, "code");
            if (rawCode == null || rawCode.isBlank()) {
                notices.add(mapFailed("warning", details("missing_required_field", "field", "code",
                        "container_id", String.valueOf(containerId)), pointer));
                continue;
            }
            String productUid = textOrNull(container, "system_code");
            if (productUid == null) {
                notices.add(mapFailed("warning", details("missing_reference", "field", "system_code",
                        "container_id", String.valueOf(containerId)), pointer));
                continue;
            }

            String code = rawCode;
            if (productUid != null) {
                String cmdbSuffix = "." + productUid;
                if (endsWithIgnoreCase(rawCode, cmdbSuffix)) {
                    code = rawCode.substring(0, rawCode.length() - cmdbSuffix.length());
                } else {
                    notices.add(cmdbSuffixNotFoundNotice(rawCode, cmdbSuffix, productUid, pointer));
                }
            }

            if (containerId != null) {
                cleanedContainerCodeById.put(containerId, code);
            }

            if (!seenContainerCodes.add(code)) {
                notices.add(duplicateKeyNotice("uid", code, "containers", containerIdx - 1, pointer));
                continue;
            }
            E2ESequenceSnapshot.ContainerDraft containerDraft = new E2ESequenceSnapshot.ContainerDraft();
            containerDraft.setUid(code);
            // ext_uid = <container_code> (cmdb suffix stripped) — per transform-spec
            // §4.2.1/§4.3.1.
            containerDraft.setExtUid(code);
            containerDraft.setName(textOrNull(container, "name"));
            containerDraft.setProductUid(productUid);
            containerDraft.setContext(pointer);
            notices.add(notice("transform.include", "info",
                    details("container_found", "raw_code", rawCode, "code", code), pointer));
            snapshot.getContainers().add(containerDraft);
        }

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

        // Step 3: per-diagram local call trees (seqno order, is_ret/use/self-call
        // dropped)
        Map<String, CallNode> diagramRoots = new LinkedHashMap<>();
        for (JsonNode diagram : root.path("diagrams")) {
            String diagramUid = textOrNull(diagram, "uid");
            if (diagramUid == null)
                continue;
            Integer diagramIdx = diagramArrayIndexByUid.get(diagramUid);
            diagramRoots.put(diagramUid, buildLocalTree(diagram, diagramUid, diagramIdx, objectsById, systemsById,
                    interfacesById, operationsByUid, notices));
        }

        // Step 4: merge child diagrams via linked_diagram_uid — one flat pass over
        // every node
        List<CallNode> allNodes = new ArrayList<>();
        for (CallNode dr : diagramRoots.values())
            collectAll(dr, allNodes);
        for (CallNode node : allNodes) {
            linkChildDiagram(node, diagramRoots, notices);
        }

        // Step 6/7: decompose the surviving tree into canonical drafts
        // bi_steps.uid/ext_uid is the business key (step_id, e.g. "Step.01.00.00.00");
        // the Sparx
        // diagram GUID (entrance_diagram_uid) is now the e2e_scenario's own identity,
        // linked to the
        // bi_step via e2e_scenario_versions.bi_step_version_id instead of the other way
        // around.
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
        Set<String> skippedOperations = new HashSet<>();

        for (CallNode node : diagramRoots.get(entranceDiagramUid).children) {

            CallNode ctx = new CallNode();
            ctx.children.add(node);
            ctx.methodAppFront = ctx.appFront = true;

            decomposeChildren(ctx, operationsByUid, interfacesById, containersById, cleanedContainerCodeById,
                    operationArrayIndexByUid, interfaceArrayIndexById, operationDrafts, registeredInterfaces,
                    skippedOperations, snapshot, notices);

            createRelations(ctx, snapshot);
        }

        dedupeOperationRelations(snapshot, notices);

        Set<String> reachableOperationUids = new LinkedHashSet<>();
        for (OperationRelationDraft relation : snapshot.getOperationRelations()) {
            if (relation.getCallerOperationExtUid() != null)
                reachableOperationUids.add(relation.getCallerOperationExtUid());
            if (relation.getCalleeOperationExtUid() != null)
                reachableOperationUids.add(relation.getCalleeOperationExtUid());
        }
        for (OperationDraft draft : operationDrafts.values()) {
            if (reachableOperationUids.contains(draft.getExtUid())) {
                snapshot.getOperations().add(draft);
            }
        }

        return new Result(snapshot, notices, stepId);
    }

    void createRelations(CallNode node, E2ESequenceSnapshot snapshot) {
        int callOrder = 0;
        for (CallNode child : node.children) {

            child.relation = new OperationRelationDraft();
            child.relation.setCallerOperationExtUid(node.operationGuid);
            child.relation.setCalleeOperationExtUid(child.operationGuid);
            child.relation.setCallOrder(callOrder++);
            child.relation.setStereotype(child.stereotype);
            child.relation.setContext(child.pointer);

            snapshot.getOperationRelations().add(child.relation);

            createRelations(child, snapshot);

        }

    }

    // A CallNode can end up reachable from more than one parent (linkChildDiagram
    // merges a child
    // diagram's root children by reference into every calling message that resolves
    // to the same
    // operation_guid — if that happens more than once, e.g. because of an
    // ambiguous_entry_point match,
    // the same child gets walked and re-emitted once per parent). Rather than
    // untangle that sharing in
    // the tree itself, dedupe the flattened relations by (caller, callee,
    // call_order): two genuinely
    // distinct calls between the same pair never share a call_order, since that's a
    // per-parent
    // positional index — only true duplicates do.
    private void dedupeOperationRelations(E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices) {
        Set<String> seen = new HashSet<>();
        List<OperationRelationDraft> deduped = new ArrayList<>();
        int idx = 0;
        for (OperationRelationDraft rel : snapshot.getOperationRelations()) {
            String key = rel.getCallerOperationExtUid() + "->" + rel.getCalleeOperationExtUid() + "@"
                    + rel.getCallOrder();
            if (seen.add(key)) {
                deduped.add(rel);
            } else {
                notices.add(
                        duplicateKeyNotice("operation_relation", key, "operation_relations", idx, rel.getContext()));
            }
            idx++;
        }
        snapshot.getOperationRelations().clear();
        snapshot.getOperationRelations().addAll(deduped);
    }

    // ------------------------------------------------------------------
    // Step 3 — per-diagram local tree (mirrors build-call-tree.mjs addMessage)
    // ------------------------------------------------------------------

    private CallNode buildLocalTree(JsonNode diagram, String diagramUid, Integer diagramIdx,
            Map<Integer, JsonNode> objectsById,
            Map<Integer, JsonNode> systemsById, Map<Integer, JsonNode> interfacesById,
            Map<String, JsonNode> operationsByUid,
            List<ArtifactNotice> notices) {
        CallNode root = new CallNode();
        root.diagramUid = diagramUid;
        root.serverId = 0;

        List<JsonNode> messages = new ArrayList<>();
        diagram.path("messages").forEach(messages::add);

        Set<String> seenMessageUids = new HashSet<>();
        List<Integer> order = new ArrayList<>();
        if (messages.isEmpty()) {
            String pointer = diagramIdx != null ? "/diagrams/" + diagramIdx : "/diagrams";
            notices.add(
                    notice("transform.exclude", "warning", details("empty_messages", "diagram_uid", diagramUid),
                            pointer));
        }
        for (int i = 0; i < messages.size(); i++) {
            String msgUid = textOrNull(messages.get(i), "uid");
            if (msgUid != null && !seenMessageUids.add(msgUid)) {
                String pointer = diagramIdx != null ? "/diagrams/" + diagramIdx + "/messages/" + i
                        : "/diagrams/messages/" + i;
                notices.add(notice("transform.exclude", "warning",
                        details("duplicate_message_row", "message_uid", msgUid), pointer));
                continue;
            }
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> intOrZero(messages.get(i), "seqno")));

        CallNode context = root;
        for (int originalIdx : order) {
            JsonNode msg = messages.get(originalIdx);
            CallNode node = wrapMessage(msg, diagramUid, diagramIdx, originalIdx, objectsById, systemsById,
                    interfacesById, operationsByUid, notices);

            if (node.isRet) {
                noteSequenceSkip(node, "is_ret", notices);
                continue;
            }
            if (node.name != null && EXCLUDED_NAMES.contains(node.name.trim().toLowerCase())) {
                noteSequenceSkip(node, "use_message", notices);
                continue;
            }
            if (Objects.equals(node.serverId, node.clientId)) {
                noteSequenceSkip(node, "self_call", notices);
                continue;
            }

            CallNode ctx = context;
            if (ctx.serverId != null && ctx.serverId == 0 || Objects.equals(node.clientId, ctx.serverId)) {
                notices.add(notice("transform.include", "info",
                        details("child_message", "parent_uid", ctx.uid, "parent_name", ctx.name, "uid", node.uid,
                                "name", node.name),
                        node.pointer));

                ctx.children.add(node);
                node.parentContext = ctx;

            } else {
                while (ctx.parentContext != null && !Objects.equals(ctx.serverId, node.clientId)) {
                    ctx = ctx.parentContext;
                }
                ctx.children.add(node);

                notices.add(notice("transform.include", "info",
                        details("child_message", "parent_uid", ctx.uid, "parent_name", ctx.name, "uid", node.uid,
                                "name", node.name),
                        node.pointer));

                node.parentContext = ctx;
            }
            context = node;
        }
        return root;
    }

    private CallNode wrapMessage(JsonNode msg, String diagramUid, Integer diagramIdx, int originalIdx,
            Map<Integer, JsonNode> objectsById,
            Map<Integer, JsonNode> systemsById, Map<Integer, JsonNode> interfacesById,
            Map<String, JsonNode> operationsByUid,
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
        notices.add(notice("transform.exclude", "warning",
                details(reason, "message_uid", node.uid, "message_name", node.name), node.pointer));
    }

    // ------------------------------------------------------------------
    // Step 4 — child diagram merge (mirrors build-call-tree.mjs Pass 2/3)
    // ------------------------------------------------------------------

    private void collectAll(CallNode node, List<CallNode> out) {
        out.add(node);
        for (CallNode ch : node.children)
            collectAll(ch, out);
    }

    private void linkChildDiagram(CallNode node, Map<String, CallNode> diagramRoots, List<ArtifactNotice> notices) {
        if (node.linkedDiagramUid == null || node.linkedDiagramUid.equals(node.diagramUid))
            return;

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
            if (Objects.equals(entry.operationGuid, operationGuid))
                matches.add(entry);
        }
        if (matches.isEmpty()) {
            notices.add(mapFailed("warning", details("entry_point_not_found", "operation_guid", operationGuid,
                    "linked_diagram_uid", node.linkedDiagramUid), node.pointer));
            return;
        }
        if (matches.size() > 1) {
            notices.add(mapFailed("warning", details("ambiguous_entry_point", "operation_guid", operationGuid,
                    "linked_diagram_uid", node.linkedDiagramUid, "matchCount", String.valueOf(matches.size())),
                    node.pointer));
        }

        for (CallNode m : matches) {
            notices.add(notice("transform.include", "info",
                    details("link_diagram", "message_name", node.name, "operation_guid", operationGuid,
                            "linked_diagram_uid", node.linkedDiagramUid, "child_message_uid", m.uid,
                            "child_message_name", m.name),
                    node.pointer));
            node.children.add(m);
        }
    }

    private void skip(CallNode parent, CallNode ch, List<CallNode> result, String reason,
            Map<String, JsonNode> operationsByUid,
            Map<Integer, JsonNode> interfacesById,
            Map<Integer, JsonNode> containersById, Map<Integer, String> cleanedContainerCodeById,
            Map<String, Integer> operationArrayIndexByUid, Map<Integer, Integer> interfaceArrayIndexById,
            Map<String, OperationDraft> operationDrafts, Set<String> registeredInterfaces,
            Set<String> skippedOperations, E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices) {
        notices.add(notice("transform.exclude", "warning",
                details("internal_call", "type", reason, "message_uid", ch.uid, "message_name", ch.name,
                        "caller", parent.serverAppCode, "callee", ch.serverAppCode, "diagram_uid", ch.diagramUid),
                ch.pointer));

        if (!(parent.methodAppFront || parent.appFront)) {
            if (ch.serverAppCode != null && parent.operationGuid != null) {
                ch.operationGuid = parent.operationGuid;
            }
            ch.serverAppCode = parent.serverAppCode;
        }

        decomposeChildren(ch, operationsByUid,
                interfacesById,
                containersById, cleanedContainerCodeById,
                operationArrayIndexByUid, interfaceArrayIndexById,
                operationDrafts, registeredInterfaces,
                skippedOperations, snapshot, notices);

        result.addAll(ch.children);
    }

    private void keep(CallNode parent, CallNode ch, List<CallNode> result,
            String reason, Map<String, JsonNode> operationsByUid,
            Map<Integer, JsonNode> interfacesById,
            Map<Integer, JsonNode> containersById, Map<Integer, String> cleanedContainerCodeById,
            Map<String, Integer> operationArrayIndexByUid, Map<Integer, Integer> interfaceArrayIndexById,
            Map<String, OperationDraft> operationDrafts, Set<String> registeredInterfaces,
            Set<String> skippedOperations, E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices) {
        /*
         * notices.add(notice("transform.include", "info",
         * details(reason, "message_uid", ch.uid, "message_name", ch.name,
         * "operation_guid", ch.operationGuid, "callee", ch.serverAppCode,
         * "parent_message_name",
         * parent.name, "parent_message_uid", parent.uid),
         * ch.pointer));
         */

        decomposeChildren(ch, operationsByUid,
                interfacesById,
                containersById, cleanedContainerCodeById,
                operationArrayIndexByUid, interfaceArrayIndexById,
                operationDrafts, registeredInterfaces,
                skippedOperations, snapshot, notices);

        result.add(ch);
    }

    // ------------------------------------------------------------------
    // Step 6/7 — decompose the surviving tree into canonical drafts
    // ------------------------------------------------------------------

    private void decomposeChildren(CallNode parent, Map<String, JsonNode> operationsByUid,
            Map<Integer, JsonNode> interfacesById,
            Map<Integer, JsonNode> containersById, Map<Integer, String> cleanedContainerCodeById,
            Map<String, Integer> operationArrayIndexByUid, Map<Integer, Integer> interfaceArrayIndexById,
            Map<String, OperationDraft> operationDrafts, Set<String> registeredInterfaces,
            Set<String> skippedOperations, E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices) {
        int callOrder = 0;
        List<CallNode> nodes = new ArrayList<>();

        for (CallNode child : parent.children) {

            if (child.appFront || child.methodAppFront) {
                skip(parent, child, nodes, "app_front", operationsByUid,
                        interfacesById,
                        containersById, cleanedContainerCodeById,
                        operationArrayIndexByUid, interfaceArrayIndexById,
                        operationDrafts, registeredInterfaces,
                        skippedOperations, snapshot, notices);
                continue;
            }

            registerOperationAndInterface(child, operationsByUid, interfacesById, containersById,
                    cleanedContainerCodeById,
                    operationArrayIndexByUid, interfaceArrayIndexById, operationDrafts, registeredInterfaces,
                    skippedOperations, snapshot, notices);

            boolean hasMethod = operationDrafts.containsKey(child.operationGuid);

            if (!hasMethod) {
                child.appFront = parent.appFront;
                child.methodAppFront = parent.methodAppFront;

                skip(parent, child, nodes, "internal_after_app_front", operationsByUid,
                        interfacesById,
                        containersById, cleanedContainerCodeById,
                        operationArrayIndexByUid, interfaceArrayIndexById,
                        operationDrafts, registeredInterfaces,
                        skippedOperations, snapshot, notices);
                continue;
            }

            if (child.methodShowInE2e && !Objects.equals(child.operationGuid, parent.operationGuid)
                    && child.serverAppCode != null) {

                keep(parent, child, nodes, "show_in_e2e", operationsByUid,
                        interfacesById,
                        containersById, cleanedContainerCodeById,
                        operationArrayIndexByUid, interfaceArrayIndexById,
                        operationDrafts, registeredInterfaces,
                        skippedOperations, snapshot, notices);
                continue;
            }

            if (Objects.equals(child.operationGuid, parent.operationGuid)) {
                skip(parent, child, nodes, "child.operation_uig==parent.operation_guid", operationsByUid,
                        interfacesById,
                        containersById, cleanedContainerCodeById,
                        operationArrayIndexByUid, interfaceArrayIndexById,
                        operationDrafts, registeredInterfaces,
                        skippedOperations, snapshot, notices);
                continue;
            }

            if (child.serverAppCode == null) {
                skip(parent, child, nodes, "child.app_code_is_null", operationsByUid,
                        interfacesById,
                        containersById, cleanedContainerCodeById,
                        operationArrayIndexByUid, interfaceArrayIndexById,
                        operationDrafts, registeredInterfaces,
                        skippedOperations, snapshot, notices);
                continue;
            }

            if (Objects.equals(child.serverAppCode, parent.serverAppCode) && !parent.appFront
                    && !parent.methodAppFront) {
                skip(parent, child, nodes, "child.app_code==parent.app_code", operationsByUid,
                        interfacesById,
                        containersById, cleanedContainerCodeById,
                        operationArrayIndexByUid, interfaceArrayIndexById,
                        operationDrafts, registeredInterfaces,
                        skippedOperations, snapshot, notices);
                continue;
            }

            child.relation = new OperationRelationDraft();
            if (parent.operationGuid == null) {
                child.relation = new OperationRelationDraft();
            }

            keep(parent, child, nodes, "ext_call", operationsByUid,
                    interfacesById,
                    containersById, cleanedContainerCodeById,
                    operationArrayIndexByUid, interfaceArrayIndexById,
                    operationDrafts, registeredInterfaces,
                    skippedOperations, snapshot, notices);
        }
        parent.children = nodes;
    }

    private void registerOperationAndInterface(CallNode node, Map<String, JsonNode> operationsByUid,
            Map<Integer, JsonNode> interfacesById,
            Map<Integer, JsonNode> containersById, Map<Integer, String> cleanedContainerCodeById,
            Map<String, Integer> operationArrayIndexByUid, Map<Integer, Integer> interfaceArrayIndexById,
            Map<String, OperationDraft> operationDrafts, Set<String> registeredInterfaces,
            Set<String> skippedOperations, E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices) {
        if (operationDrafts.containsKey(node.operationGuid) || skippedOperations.contains(node.operationGuid))
            return;

        // G2: operation_guid present on the message but not found in operations[] —
        // distinct from G1
        // below (operation exists but its interface doesn't); conflating the two under
        // one reason
        // made root-causing confusing (a bogus operation_guid was being reported as
        // "missing_interface").
        JsonNode op = operationsByUid.get(node.operationGuid);
        if (op == null) {
            skippedOperations.add(node.operationGuid);
            notices.add(missingOperationNotice(node.operationGuid, node.uid, node.pointer));
            return;
        }

        Integer ifaceId = intOrNull(op, "interface_id");
        JsonNode iface = ifaceId != null ? interfacesById.get(ifaceId) : null;

        // G1: the operation resolved, but its interface_id doesn't — or the interface
        // exists but has no
        // code (the Sparx extract query LEFT JOINs the api wiring now, so an interface
        // with
        // unresolvable wiring still appears in interfaces[], just with code=null).
        // Dropped entirely, not
        // just left with a null interfaceUid — fdm-products rejects
        // operations.parentInterfaceCode == null.
        if (iface == null || textOrNull(iface, "code") == null) {
            skippedOperations.add(node.operationGuid);
            notices.add(missingInterfaceNotice(node.operationGuid, ifaceId, node.pointer));
            return;
        }

        OperationDraft draft = new OperationDraft();
        draft.setExtUid(node.operationGuid);
        String rawName = textOrNull(op, "name");
        // Points at root.operations[N] itself, not the calling message.
        Integer opIdx = operationArrayIndexByUid.get(node.operationGuid);
        draft.setContext(opIdx != null ? "/operations/" + opIdx : node.pointer);

        Map<String, String> tags = tagsOf(op);
        Double rps = parseSlaField(tags, "rps", node, notices);
        Double latency = parseSlaField(tags, "latency", node, notices);
        Double errorRate = parseSlaField(tags, "error_rate", node, notices);
        draft.setRps(rps);
        draft.setLatency(latency);
        draft.setErrorRate(errorRate);
        if (!tags.isEmpty()) {
            notices.add(implicitCastNotice(node, tags, rps, latency, errorRate));
        }

        Integer containerId = intOrNull(iface, "container_id");
        JsonNode containerNode = containerId != null ? containersById.get(containerId) : null;
        String cleanedContainerUid = containerId != null ? cleanedContainerCodeById.get(containerId) : null;

        String systemCode = textOrNull(containerNode, "system_code");
        if (systemCode == null) {
            notices.add(notice("transform.exclude", "warning",
                    details("system_code_is_null", "message_name", node.name, "message_uid", node.uid), node.pointer));
            return;
        }

        // interface uid — per transform-spec §4.2.2 (v6): strip the trailing
        // ".<original container
        // code>" suffix (case-insensitive) from interfaces[].code. Uses the container's
        // ORIGINAL
        // (uncleaned) code, since that's what the interface's own code was suffixed
        // with in Sparx.
        String rawIfaceCode = textOrNull(iface, "code");
        String ifaceUid = rawIfaceCode;
        if (rawIfaceCode == null) {
            notices.add(notice("transform.exclude", "warning",
                    details("interface_code_is_null", "message_uid", node.uid, "message_name", node.name),
                    node.pointer));
            return;
        }

        if (containerNode == null) {
            notices.add(notice("transform.exclude", "warning",
                    details("container_node_is_null", "message_uid", node.uid, "message_name", node.name),
                    node.pointer));
            return;
        }

        String originalContainerCode = textOrNull(containerNode, "code");
        if (originalContainerCode != null) {
            String containerSuffix = "." + originalContainerCode;
            if (endsWithIgnoreCase(rawIfaceCode, containerSuffix)) {
                String extracted = rawIfaceCode.substring(0, rawIfaceCode.length() - containerSuffix.length());
                if (extracted.isEmpty()) {
                    // G13: empty interface_code after suffix strip is fatal for this interface —
                    // the operation (and its subtree) is dropped, same as G1.
                    skippedOperations.add(node.operationGuid);
                    notices.add(emptyInterfaceCodeNotice(rawIfaceCode, containerSuffix, node.pointer));
                    return;
                }
                ifaceUid = extracted;
            } else {
                notices.add(containerSuffixNotFoundNotice(rawIfaceCode, containerSuffix, originalContainerCode,
                        node.pointer));
            }
        } else {
            notices.add(notice("transform.exclude", "warning",
                    details("container_code_is_null", "message_uid", node.uid, "message_name", node.name),
                    node.pointer));
            return;
        }

        if (ifaceUid == null) {
            notices.add(notice("transform.exclude", "warning",
                    details("interface_not_found", "message_name", node.name), node.pointer));
            return;
        }

        draft.setInterfaceUid(ifaceUid);

        String rawProtocol = tagsOf(iface).get("protocol");
        // Default to UNKNOWN when the source has no protocol tag at all — per
        // transform-spec §4.4.
        String protocol = (rawProtocol == null || rawProtocol.isBlank()) ? "UNKNOWN" : rawProtocol;

        if (registeredInterfaces.add(ifaceUid)) {
            InterfaceDraft ifaceDraft = new InterfaceDraft();
            ifaceDraft.setUid(ifaceUid);
            // ext_uid = <interface_code> (container suffix stripped) — per transform-spec
            // §4.2.2/§4.4.
            ifaceDraft.setExtUid(ifaceUid);
            ifaceDraft.setProtocol(protocol);
            ifaceDraft.setName(textOrNull(iface, "name"));
            ifaceDraft.setSource(textOrNull(iface, "source"));
            Integer ifaceIdx = interfaceArrayIndexById.get(ifaceId);
            String ifacePointer = ifaceIdx != null ? "/interfaces/" + ifaceIdx : null;
            ifaceDraft.setContext(ifacePointer);

            if (cleanedContainerUid != null) {
                ifaceDraft.setContainerUid(cleanedContainerUid);
            } else if (containerId != null) {
                notices.add(mapFailed("warning", details("missing_reference", "field", "container_id",
                        "value", String.valueOf(containerId)), ifacePointer != null ? ifacePointer : node.pointer));
            }
            snapshot.getInterfaces().add(ifaceDraft);

            if (rawProtocol == null || rawProtocol.isBlank()) {
                notices.add(protocolDefaultNotice(ifaceUid, ifacePointer != null ? ifacePointer : node.pointer));
            }
        }

        // name — per transform-spec §4.5: if the raw name has a space, name becomes
        // everything after
        // the first word (regardless of protocol).
        String name = rawName;
        if (rawName != null && rawName.indexOf(' ') >= 0) {
            name = rawName.substring(rawName.indexOf(' ') + 1);
            notices.add(nameExtractedNotice(node, rawName, name));
        }
        draft.setName(name);

        // type — per transform-spec §4.5.1 (v6): REST derives type from the first word
        // of the raw name
        // (or UNKNOWN if there's no space, warning). UNKNOWN protocol now attempts the
        // same extraction
        // (assuming REST, warning either way) instead of just inheriting "UNKNOWN". Any
        // other known
        // protocol (SOAP, gRPC, ...) is inherited directly.
        boolean hasSpace = rawName != null && rawName.indexOf(' ') >= 0;
        String type;
        if ("REST".equalsIgnoreCase(protocol)) {
            if (hasSpace) {
                type = rawName.substring(0, rawName.indexOf(' '));
                notices.add(typeExtractedNotice(node, rawName, type, protocol));
            } else {
                type = "UNKNOWN";
                notices.add(typeUnknownNotice(node, rawName, protocol, "rest_no_space_in_name"));
            }
        } else if ("UNKNOWN".equals(protocol)) {
            if (hasSpace) {
                type = rawName.substring(0, rawName.indexOf(' '));
                notices.add(typeAssumedRestNotice(node, rawName, type));
            } else {
                type = "UNKNOWN";
                notices.add(typeUnknownNotice(node, rawName, protocol, "unknown_protocol_no_space"));
            }
        } else {
            type = protocol;
            notices.add(typeInheritedNotice(node, rawName, protocol));
        }
        draft.setType(type);

        notices.add(notice("transform.include", "info",
                details("operation_found", "operation_uid", node.operationGuid, "operation_name", draft.getName()),
                node.pointer));

        // Not added to snapshot.getOperations() here: registration runs before
        // decomposeChildren's
        // keep/skip decision, so a call later collapsed as purely-internal (e.g. same
        // app code as its
        // parent) would otherwise leak its operation into the output as an orphan with
        // no relation
        // pointing to it. decompose() adds only operations actually reachable via a
        // surviving relation.
        operationDrafts.put(node.operationGuid, draft);
    }

    private Double parseSlaField(Map<String, String> tags, String field, CallNode node, List<ArtifactNotice> notices) {
        String raw = tags.get(field);
        if (raw == null)
            return null;
        Double parsed = numOrNull(raw);
        if (parsed == null) {
            notices.add(slaParseFailedNotice(field, raw, node));
        }
        return parsed;
    }

    private ArtifactNotice missingInterfaceNotice(String operationUid, Integer interfaceId, String pointer) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", "missing_interface");
        d.put("field", "interface_id");
        d.put("value", interfaceId != null ? String.valueOf(interfaceId) : null);
        d.put("operation_uid", operationUid);
        return notice("transform.exclude", "warning", d, pointer);
    }

    private ArtifactNotice missingOperationNotice(String operationGuid, String messageUid, String pointer) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", "missing_operation");
        d.put("field", "operation_guid");
        d.put("value", operationGuid);
        d.put("message_uid", messageUid);
        return notice("transform.exclude", "warning", d, pointer);
    }

    private ArtifactNotice slaParseFailedNotice(String field, String rawValue, CallNode node) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("field", "sla." + field);
        d.put("source_value", rawValue);
        d.put("target_value", null);
        d.put("reason", "parse_failed");
        d.put("operation_uid", node.operationGuid);
        return notice("transform.implicit_cast", "warning", d, node.pointer);
    }

    private ArtifactNotice cmdbSuffixNotFoundNotice(String originalCode, String expectedSuffix, String systemCode,
            String pointer) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", "cmdb_suffix_not_found");
        d.put("field", "container_code");
        d.put("original_code", originalCode);
        d.put("expected_suffix", expectedSuffix);
        d.put("system_code", systemCode);
        d.put("action", "used_original_code");
        return mapFailed("warning", d, pointer);
    }

    private ArtifactNotice containerSuffixNotFoundNotice(String originalCode, String expectedSuffix,
            String containerCode, String pointer) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", "container_suffix_not_found");
        d.put("field", "interface_code");
        d.put("original_code", originalCode);
        d.put("expected_suffix", expectedSuffix);
        d.put("container_code", containerCode);
        d.put("action", "used_original_code");
        return mapFailed("warning", d, pointer);
    }

    private ArtifactNotice emptyInterfaceCodeNotice(String originalCode, String containerSuffix, String pointer) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", "empty_interface_code_after_suffix_strip");
        d.put("field", "interface_code");
        d.put("original_code", originalCode);
        d.put("container_suffix", containerSuffix);
        d.put("action", "interface_skipped");
        return notice("transform.exclude", "error", d, pointer);
    }

    private ArtifactNotice duplicateKeyNotice(String field, String value, String block, int duplicateIndex,
            String pointer) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", "duplicate_key");
        d.put("field", field);
        d.put("value", value);
        d.put("block", block);
        d.put("duplicate_index", duplicateIndex);
        return notice("transform.exclude", "warning", d, pointer);
    }

    private ArtifactNotice protocolDefaultNotice(String interfaceUid, String pointer) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("field", "protocol");
        d.put("action", "default_value");
        d.put("value", "UNKNOWN");
        d.put("reason", "protocol_tag_not_set");
        d.put("interface_uid", interfaceUid);
        return notice("transform.implicit_cast", "warning", d, pointer);
    }

    private ArtifactNotice nameExtractedNotice(CallNode node, String sourceValue, String targetValue) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("field", "name");
        d.put("action", "extract_after_first_word");
        d.put("source_value", sourceValue);
        d.put("target_value", targetValue);
        return notice("transform.implicit_cast", "info", d, node.pointer);
    }

    private ArtifactNotice typeExtractedNotice(CallNode node, String sourceValue, String targetValue, String protocol) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("field", "type");
        d.put("action", "extract_before_first_space");
        d.put("source_value", sourceValue);
        d.put("target_value", targetValue);
        d.put("interface_protocol", protocol);
        d.put("operation_uid", node.operationGuid);
        return notice("transform.implicit_cast", "info", d, node.pointer);
    }

    private ArtifactNotice typeUnknownNotice(CallNode node, String sourceValue, String protocol, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("field", "type");
        d.put("action", "cannot_extract");
        d.put("source_value", sourceValue);
        d.put("target_value", "UNKNOWN");
        d.put("interface_protocol", protocol);
        d.put("reason", reason);
        d.put("operation_uid", node.operationGuid);
        return notice("transform.implicit_cast", "warning", d, node.pointer);
    }

    private ArtifactNotice typeAssumedRestNotice(CallNode node, String sourceValue, String targetValue) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("field", "type");
        d.put("action", "extract_before_first_space");
        d.put("source_value", sourceValue);
        d.put("target_value", targetValue);
        d.put("interface_protocol", "UNKNOWN");
        d.put("reason", "unknown_protocol_assumed_rest");
        d.put("operation_uid", node.operationGuid);
        return notice("transform.implicit_cast", "warning", d, node.pointer);
    }

    private ArtifactNotice typeInheritedNotice(CallNode node, String sourceValue, String protocol) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("field", "type");
        d.put("action", "inherited_from_interface");
        d.put("source_value", sourceValue);
        d.put("target_value", protocol);
        d.put("interface_protocol", protocol);
        d.put("operation_uid", node.operationGuid);
        return notice("transform.implicit_cast", "info", d, node.pointer);
    }

    private ArtifactNotice implicitCastNotice(CallNode node, Map<String, String> tags, Double rps, Double latency,
            Double errorRate) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("operation_guid", node.operationGuid);
        if (tags.get("rps") != null)
            d.put("rps", Map.of("source_value", tags.get("rps"), "target_value", String.valueOf(rps)));
        if (tags.get("latency") != null)
            d.put("latency", Map.of("source_value", tags.get("latency"), "target_value", String.valueOf(latency)));
        if (tags.get("error_rate") != null)
            d.put("error_rate",
                    Map.of("source_value", tags.get("error_rate"), "target_value", String.valueOf(errorRate)));
        return notice("transform.implicit_cast", "info", d, node.pointer);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String parseStepId(String notes) {
        if (notes == null)
            return null;
        Matcher m = STEP_ID_PATTERN.matcher(notes);
        return m.find() ? m.group(1) : null;
    }

    private ArtifactNotice mapFailed(String level, Map<String, Object> details, String pointer) {
        return notice("transform.map_failed", level, details, pointer);
    }

    private ArtifactNotice notice(String code, String level, Map<String, Object> details, String pointer) {
        return new ArtifactNotice(null, null, code, level, "transform", null, null, null, null, code, toJson(details),
                pointer, null);
    }

    private Map<String, Object> details(String reason, Object... kv) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", reason);
        for (int i = 0; i < kv.length; i += 2)
            d.put(String.valueOf(kv[i]), kv[i + 1]);
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
            if (key != null)
                map.put(key, item);
        }
        return map;
    }

    private Map<Integer, JsonNode> indexByIntField(JsonNode array, String field) {
        Map<Integer, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode item : array) {
            Integer key = intOrNull(item, field);
            if (key != null)
                map.put(key, item);
        }
        return map;
    }

    private Map<Integer, Integer> arrayIndexByIntField(JsonNode array, String field) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        int idx = 0;
        for (JsonNode item : array) {
            Integer key = intOrNull(item, field);
            if (key != null)
                map.put(key, idx);
            idx++;
        }
        return map;
    }

    private Map<String, Integer> arrayIndexByStringField(JsonNode array, String field) {
        Map<String, Integer> map = new LinkedHashMap<>();
        int idx = 0;
        for (JsonNode item : array) {
            String key = textOrNull(item, field);
            if (key != null)
                map.put(key, idx);
            idx++;
        }
        return map;
    }

    private Map<String, String> tagsOf(JsonNode entity) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : entity.path("tags")) {
            String property = textOrNull(tag, "property");
            String value = textOrNull(tag, "value");
            if (property != null)
                tags.put(property, value);
        }
        return tags;
    }

    private static String resolveAppCode(JsonNode obj, Map<Integer, JsonNode> systemsById) {
        if (obj == null)
            return null;
        Integer systemId = intOrNull(obj, "system_id");
        JsonNode system = systemId != null ? systemsById.get(systemId) : null;
        String code = system != null ? textOrNull(system, "code") : null;
        return code != null ? code : textOrNull(obj, "alias");
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode())
            return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode())
            return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private static int intOrZero(JsonNode node, String field) {
        Integer v = intOrNull(node, field);
        return v != null ? v : 0;
    }

    private static boolean endsWithIgnoreCase(String value, String suffix) {
        int offset = value.length() - suffix.length();
        return offset >= 0 && value.regionMatches(true, offset, suffix, 0, suffix.length());
    }

    private static Double numOrNull(String value) {
        if (value == null)
            return null;
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
        OperationRelationDraft relation;
    }
}
