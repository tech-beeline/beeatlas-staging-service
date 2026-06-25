package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.BiStepDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.BiStepRelationDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.InterfaceDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.OperationDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.OperationRelationDraft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transformer for artifactType=e2e-sequence. Maps the dashboard-main "Scenario" JSON
 * (GET /api/v4/e2e/scenarios/{uid}/sequence) into E2ESequenceSnapshot, the private
 * entity-graph shape this module's saver (E2ECanonicalSaver) understands.
 *
 * Real response shape (see a sample export, not dashboard-main's source — the two disagree):
 *   root: { name, uid, note, sequence: [...], interfaces: [...] }
 *   sequence node: { name, uid, stereotype, rps, latency, error_rate, operation_guid,
 *                     diagram_uid, seqno, client_name, client_code, server_name, server_code,
 *                     is_ret, linked_diagram_uid, sequence: [...] (optional, nested) }
 *   interfaces[]: { id, name, app_code, code, uid, source?, methods: [...] }
 *   interfaces[].methods[]: { name, uid, api_id, rps, latency, error_rate }
 *
 * There is no "message.method" sub-object — each sequence node IS the call, identified by
 * its own operation_guid (matches interfaces[].methods[].uid when declared there).
 *
 * Layering: only the FIRST layer of root.sequence[] becomes a BI step (one per root-level
 * call) — these represent the BI-level steps of the scenario. A BI step's own operation_guid
 * is recorded as a bi_step_relation_versions row (call_order=0). Everything nested beneath a
 * root item (sequence-sequence-sequence...) is an operation-to-operation call chain, recorded
 * in operation_relation_versions with the immediate parent's operation_guid as caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class E2ESequenceTransformer implements ArtifactTransformer {

    public static final String MODULE_CODE = "e2e-sequence-transformer";

    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Maps dashboard's e2e scenario JSON into the BI step / interface / operation snapshot"; }

    @Override
    public E2ESequenceSnapshot transform(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);
        E2ESequenceSnapshot snapshot = new E2ESequenceSnapshot();
        String scenarioName = textOrNull(root, "name");

        Map<String, OperationDraft> operationsByExtUid = new LinkedHashMap<>();
        mapInterfacesAndOperations(root.path("interfaces"), snapshot, operationsByExtUid);
        mapRootSequence(root.path("sequence"), scenarioName, artifactUid, snapshot, operationsByExtUid);

        log.info("Transformed e2e-sequence uid={}: interfaces={}, operations={}, biSteps={}, biStepRelations={}, operationRelations={}",
                artifactUid, snapshot.getInterfaces().size(), snapshot.getOperations().size(),
                snapshot.getBiSteps().size(), snapshot.getBiStepRelations().size(), snapshot.getOperationRelations().size());

        return snapshot;
    }

    private void mapInterfacesAndOperations(JsonNode interfaces, E2ESequenceSnapshot snapshot,
                                              Map<String, OperationDraft> operationsByExtUid) {
        if (!interfaces.isArray()) return;

        int ifaceIdx = 0;
        for (JsonNode iface : interfaces) {
            String ifaceUid = textOrNull(iface, "uid");
            if (ifaceUid == null) { ifaceIdx++; continue; }

            InterfaceDraft ifaceDraft = new InterfaceDraft();
            ifaceDraft.setUid(ifaceUid);
            ifaceDraft.setProtocol(textOrNull(iface, "source"));
            snapshot.getInterfaces().add(ifaceDraft);

            int methodIdx = 0;
            for (JsonNode method : iface.path("methods")) {
                String methodUid = textOrNull(method, "uid");
                if (methodUid != null && !operationsByExtUid.containsKey(methodUid)) {
                    OperationDraft op = new OperationDraft();
                    op.setExtUid(methodUid);
                    op.setInterfaceUid(ifaceUid);
                    op.setName(nameOrFallback(textOrNull(method, "name"), methodUid));
                    op.setRps(doubleOrNull(method, "rps"));
                    op.setLatency(doubleOrNull(method, "latency"));
                    op.setErrorRate(doubleOrNull(method, "error_rate"));
                    op.setContext("/interfaces/" + ifaceIdx + "/methods/" + methodIdx);
                    operationsByExtUid.put(methodUid, op);
                    snapshot.getOperations().add(op);
                }
                methodIdx++;
            }
            ifaceIdx++;
        }
    }

    /** First layer of root.sequence[] — each item becomes a BI step. */
    private void mapRootSequence(JsonNode rootSequence, String scenarioName, String artifactUid,
                                   E2ESequenceSnapshot snapshot, Map<String, OperationDraft> operationsByExtUid) {
        if (!rootSequence.isArray()) return;

        int rootIdx = 0;
        for (JsonNode rootItem : rootSequence) {
            String pointer = "/sequence/" + rootIdx;
            String stepUid = textOrNull(rootItem, "uid");
            String operationGuid = textOrNull(rootItem, "operation_guid");

            if (stepUid != null) {
                BiStepDraft step = new BiStepDraft();
                step.setUid(stepUid);
                step.setName(scenarioName);
                step.setRps(doubleOrNull(rootItem, "rps"));
                step.setLatency(doubleOrNull(rootItem, "latency"));
                step.setErrorRate(doubleOrNull(rootItem, "error_rate"));
                step.setContext(pointer);
                step.setExternalGuid(artifactUid);
                step.setSourceId(textOrNull(rootItem, "diagram_uid"));
                snapshot.getBiSteps().add(step);

                if (operationGuid != null) {
                    ensureOperation(operationGuid, rootItem, pointer, operationsByExtUid, snapshot);

                    BiStepRelationDraft relation = new BiStepRelationDraft();
                    relation.setBiStepUid(stepUid);
                    relation.setOperationExtUid(operationGuid);
                    relation.setCallOrder(0);
                    relation.setStereotype(textOrNull(rootItem, "stereotype"));
                    relation.setContext(pointer);
                    snapshot.getBiStepRelations().add(relation);

                    mapOperationSequence(rootItem.path("sequence"), operationGuid, pointer, snapshot, operationsByExtUid);
                }
            }
            rootIdx++;
        }
    }

    /** Everything nested below the first layer — operation-to-operation call chain. */
    private void mapOperationSequence(JsonNode nodes, String parentOperationGuid, String parentPointer,
                                        E2ESequenceSnapshot snapshot, Map<String, OperationDraft> operationsByExtUid) {
        if (!nodes.isArray()) return;

        int callOrder = 0;
        for (JsonNode node : nodes) {
            String pointer = parentPointer + "/sequence/" + callOrder;
            String operationGuid = textOrNull(node, "operation_guid");

            if (operationGuid != null) {
                ensureOperation(operationGuid, node, pointer, operationsByExtUid, snapshot);

                OperationRelationDraft relation = new OperationRelationDraft();
                relation.setCallerOperationExtUid(parentOperationGuid);
                relation.setCalleeOperationExtUid(operationGuid);
                relation.setCallOrder(callOrder);
                relation.setStereotype(textOrNull(node, "stereotype"));
                relation.setContext(pointer);
                snapshot.getOperationRelations().add(relation);

                mapOperationSequence(node.path("sequence"), operationGuid, pointer, snapshot, operationsByExtUid);
            }
            callOrder++;
        }
    }

    private void ensureOperation(String operationGuid, JsonNode node, String pointer,
                                   Map<String, OperationDraft> operationsByExtUid, E2ESequenceSnapshot snapshot) {
        if (operationsByExtUid.containsKey(operationGuid)) return;

        OperationDraft op = new OperationDraft();
        op.setExtUid(operationGuid);
        op.setName(nameOrFallback(textOrNull(node, "name"), operationGuid));
        op.setRps(doubleOrNull(node, "rps"));
        op.setLatency(doubleOrNull(node, "latency"));
        op.setErrorRate(doubleOrNull(node, "error_rate"));
        op.setContext(pointer);
        operationsByExtUid.put(operationGuid, op);
        snapshot.getOperations().add(op);
    }

    /** operation_versions.name is NOT NULL; Dashboard occasionally omits a call's name. */
    private static String nameOrFallback(String name, String fallback) {
        return name != null && !name.isBlank() ? name : fallback;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }
}
