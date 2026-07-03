package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.TransformResult;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.BiStepDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.BiStepRelationDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.InterfaceDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.OperationDraft;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot.OperationRelationDraft;

import java.util.LinkedHashMap;
import java.util.Map;

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
    public TransformResult transform(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);
        E2ESequenceSnapshot snapshot = new E2ESequenceSnapshot();
        String scenarioName = textOrNull(root, "name");

        Map<String, OperationDraft> operationsByExtUid = new LinkedHashMap<>();
        mapInterfacesAndOperations(root.path("interfaces"), snapshot, operationsByExtUid);
        mapRootSequence(root.path("sequence"), scenarioName, artifactUid, snapshot, operationsByExtUid);

        log.info("Transformed e2e-sequence uid={}: interfaces={}, operations={}, biSteps={}, biStepRelations={}, operationRelations={}",
                artifactUid, snapshot.getInterfaces().size(), snapshot.getOperations().size(),
                snapshot.getBiSteps().size(), snapshot.getBiStepRelations().size(), snapshot.getOperationRelations().size());

        return TransformResult.of(snapshot);
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

    private void mapRootSequence(JsonNode rootSequence, String scenarioName, String artifactUid,
                                   E2ESequenceSnapshot snapshot, Map<String, OperationDraft> operationsByExtUid) {
        if (!rootSequence.isArray()) return;

        BiStepDraft scenario = new BiStepDraft();
        scenario.setUid(artifactUid);
        scenario.setName(scenarioName);
        scenario.setExternalGuid(artifactUid);
        snapshot.getBiSteps().add(scenario);

        int rootIdx = 0;
        for (JsonNode rootItem : rootSequence) {
            String pointer = "/sequence/" + rootIdx;
            String operationGuid = textOrNull(rootItem, "operation_guid");

            if (operationGuid != null) {
                ensureOperation(operationGuid, rootItem, pointer, operationsByExtUid, snapshot);

                BiStepRelationDraft relation = new BiStepRelationDraft();
                relation.setBiStepUid(artifactUid);
                relation.setOperationExtUid(operationGuid);
                relation.setCallOrder(rootIdx);
                relation.setStereotype(textOrNull(rootItem, "stereotype"));
                relation.setContext(pointer);
                snapshot.getBiStepRelations().add(relation);

                mapOperationSequence(rootItem.path("sequence"), operationGuid, pointer, snapshot, operationsByExtUid);
            }
            rootIdx++;
        }
    }

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
