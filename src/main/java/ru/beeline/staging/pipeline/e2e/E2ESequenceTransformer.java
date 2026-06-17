package ru.beeline.staging.pipeline.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.ArtifactTransformer;
import ru.beeline.staging.pipeline.CanonicalSnapshot;
import ru.beeline.staging.pipeline.CanonicalSnapshot.BiStepDraft;
import ru.beeline.staging.pipeline.CanonicalSnapshot.BiStepRelationDraft;
import ru.beeline.staging.pipeline.CanonicalSnapshot.InterfaceDraft;
import ru.beeline.staging.pipeline.CanonicalSnapshot.OperationDraft;
import ru.beeline.staging.pipeline.CanonicalSnapshot.OperationRelationDraft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transformer for artifactType=e2e-sequence. Maps the dashboard-main "Scenario" JSON
 * (the same shape served by GET /api/v4/e2e/scenarios/{uid}/sequence, see
 * dashboard-main/src/api/model/scenario/index.mjs Scenario#toJSON) into the canonical model:
 *
 *   interfaces[]                  -> staging.interfaces / interface_versions
 *   interfaces[*].methods[]       -> staging.operations / operation_versions
 *   sequence (recursive messages) -> staging.bi_steps / bi_step_versions
 *   message.method                -> staging.bi_step_relation_versions  (step calls operation)
 *   parent.method -> child.method -> staging.operation_relation_versions (call chain)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class E2ESequenceTransformer implements ArtifactTransformer {

    private final ObjectMapper objectMapper;

    @Override
    public String supportedType() { return DashboardE2ELoader.TYPE; }

    @Override
    public CanonicalSnapshot transform(String artifactUid, byte[] rawBytes) throws Exception {
        JsonNode root = objectMapper.readTree(rawBytes);
        CanonicalSnapshot snapshot = new CanonicalSnapshot();

        Map<String, OperationDraft> operationsByExtUid = new LinkedHashMap<>();
        mapInterfacesAndOperations(root.path("interfaces"), snapshot, operationsByExtUid);
        mapSequence(root.path("sequence"), null, 0, snapshot, operationsByExtUid);

        log.info("Transformed e2e-sequence uid={}: interfaces={}, operations={}, biSteps={}, biStepRelations={}, operationRelations={}",
                artifactUid, snapshot.getInterfaces().size(), snapshot.getOperations().size(),
                snapshot.getBiSteps().size(), snapshot.getBiStepRelations().size(), snapshot.getOperationRelations().size());

        return snapshot;
    }

    private void mapInterfacesAndOperations(JsonNode interfaces, CanonicalSnapshot snapshot,
                                              Map<String, OperationDraft> operationsByExtUid) {
        if (!interfaces.isArray()) return;

        for (JsonNode iface : interfaces) {
            String ifaceUid = textOrNull(iface, "uid");
            if (ifaceUid == null) continue;

            InterfaceDraft ifaceDraft = new InterfaceDraft();
            ifaceDraft.setUid(ifaceUid);
            ifaceDraft.setProtocol(textOrNull(iface, "source"));
            snapshot.getInterfaces().add(ifaceDraft);

            for (JsonNode method : iface.path("methods")) {
                String methodUid = textOrNull(method, "uid");
                if (methodUid == null || operationsByExtUid.containsKey(methodUid)) continue;

                OperationDraft op = new OperationDraft();
                op.setExtUid(methodUid);
                op.setInterfaceUid(ifaceUid);
                op.setName(textOrNull(method, "name"));
                op.setRps(doubleOrNull(method, "rps"));
                op.setLatency(doubleOrNull(method, "latency"));
                op.setErrorRate(doubleOrNull(method, "error_rate"));
                operationsByExtUid.put(methodUid, op);
                snapshot.getOperations().add(op);
            }
        }
    }

    private void mapSequence(JsonNode messages, JsonNode parentMessage, int depth,
                              CanonicalSnapshot snapshot, Map<String, OperationDraft> operationsByExtUid) {
        if (!messages.isArray()) return;

        int callOrder = 0;
        for (JsonNode message : messages) {
            String msgUid = textOrNull(message, "uid");
            if (msgUid != null) {
                BiStepDraft step = new BiStepDraft();
                step.setUid(msgUid);
                step.setName(textOrNull(message, "name"));
                JsonNode method = message.path("method");
                step.setRps(doubleOrNull(method, "rps"));
                step.setLatency(doubleOrNull(method, "latency"));
                step.setErrorRate(doubleOrNull(method, "error_rate"));
                step.setContext(textOrNull(message, "client_name") + " -> " + textOrNull(message, "server_name"));
                snapshot.getBiSteps().add(step);

                String methodUid = textOrNull(method, "uid");
                if (methodUid != null) {
                    if (!operationsByExtUid.containsKey(methodUid)) {
                        log.warn("e2e-sequence: message uid={} references operation uid={} not declared in interfaces[] — adding without interface link",
                                msgUid, methodUid);
                        OperationDraft fallback = new OperationDraft();
                        fallback.setExtUid(methodUid);
                        fallback.setName(textOrNull(method, "name"));
                        fallback.setRps(doubleOrNull(method, "rps"));
                        fallback.setLatency(doubleOrNull(method, "latency"));
                        fallback.setErrorRate(doubleOrNull(method, "error_rate"));
                        operationsByExtUid.put(methodUid, fallback);
                        snapshot.getOperations().add(fallback);
                    }

                    BiStepRelationDraft relation = new BiStepRelationDraft();
                    relation.setBiStepUid(msgUid);
                    relation.setOperationExtUid(methodUid);
                    relation.setCallOrder(callOrder);
                    snapshot.getBiStepRelations().add(relation);

                    String parentMethodUid = parentMessage != null ? textOrNull(parentMessage.path("method"), "uid") : null;
                    if (parentMethodUid != null) {
                        OperationRelationDraft opRelation = new OperationRelationDraft();
                        opRelation.setCallerOperationExtUid(parentMethodUid);
                        opRelation.setCalleeOperationExtUid(methodUid);
                        opRelation.setCallOrder(callOrder);
                        snapshot.getOperationRelations().add(opRelation);
                    }
                }
            }

            mapSequence(message.path("sequence"), message, depth + 1, snapshot, operationsByExtUid);
            callOrder++;
        }
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
