/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.e2e.CmdbAliasLookup;
import ru.beeline.staging.e2e.ParseOutcome;
import ru.beeline.staging.e2e.ParsedDiagram;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.product.dto.search.MatchedArchOperation;
import ru.beeline.staging.product.dto.search.OperationMatchCandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlantUmlE2eDecomposer {

    public static final String EXCLUDE = "e2e_plantuml.transform.exclude";
    public static final String IMPLICIT_CAST = "e2e_plantuml.transform.implicit_cast";
    public static final String PARSE_FAILED = "e2e_plantuml.transform.error.parse_failed";
    public static final String SEARCH_UNAVAILABLE = "e2e_plantuml.transform.search_matched_unavailable";
    public static final String NOT_IN_LANDSCAPE = "e2e_plantuml.transform.operation_not_in_landscape";

    private static final Pattern REST_CALL = Pattern.compile(
            "(?i)\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(\\S+)");
    private static final String DEFAULT_PROTOCOL = "UNKNOWN";
    public static final String UNKNOWN_REQUEST = "e2e_plantuml.transform.unknown_request";
    private static final String UNKNOWN_TYPE = "UNKNOWN";
    private static final int MAX_PATH_LENGTH = 255;
    private static final int UID_LENGTH = 32;

    private final PlantUmlDiagramParser parser;
    private final CmdbAliasLookup cmdbAliasLookup;
    private final ProductServiceClient productServiceClient;
    private final ObjectMapper objectMapper;

    public record Result(E2ESequenceSnapshot snapshot, List<ArtifactNotice> notices) {}

    public Result decompose(String plantUmlText, String artifactUid, String name, String biStepCode) {
        List<ArtifactNotice> notices = new ArrayList<>();
        E2ESequenceSnapshot snapshot = new E2ESequenceSnapshot();
        snapshot.setE2eScenario(scenario(artifactUid, name, biStepCode));
        if (biStepCode != null && !biStepCode.isBlank()) {
            snapshot.getBiSteps().add(biStep(biStepCode));
        }

        ParseOutcome outcome = parser.parse(plantUmlText);
        if (!outcome.isParsed()) {
            notices.add(notice(PARSE_FAILED, "error", Map.of("artifactUid", artifactUid,
                    "reason", outcome.findings().isEmpty() ? "unparseable" : outcome.findings().get(0).message())));
            return new Result(snapshot, notices);
        }

        ParsedDiagram diagram = outcome.diagram();
        Map<String, CmdbAliasLookup.ResolvedParticipant> resolved = resolveParticipants(diagram);
        List<Call> calls = collectCalls(diagram, resolved, notices);
        List<OperationMatchCandidate> candidates = candidatesOf(calls);
        if (candidates.isEmpty()) {
            return new Result(snapshot, notices);
        }

        List<MatchedArchOperation> matches;
        try {
            matches = productServiceClient.searchMatchedOperations(candidates);
        } catch (RuntimeException e) {
            notices.add(notice(SEARCH_UNAVAILABLE, "error", Map.of("artifactUid", artifactUid,
                    "reason", String.valueOf(e.getMessage()))));
            return new Result(snapshot, notices);
        }

        Map<String, MatchedArchOperation> matchByKey = indexMatches(matches);
        buildSnapshot(snapshot, calls, matchByKey, notices);
        return new Result(snapshot, notices);
    }

    private record Call(ParsedDiagram.Message message, String method, String path,
                        CmdbAliasLookup.ResolvedParticipant receiver) {

        String productCode() {
            return receiver.productAlias() != null ? receiver.productAlias() : receiver.alias();
        }
    }

    private Map<String, CmdbAliasLookup.ResolvedParticipant> resolveParticipants(ParsedDiagram diagram) {
        Set<String> keys = new LinkedHashSet<>();
        for (ParsedDiagram.Participant participant : diagram.participants()) {
            if (participant.name() != null && !participant.name().isBlank()) {
                keys.add(participant.name());
                int dot = participant.name().indexOf('.');
                if (dot > 0) {
                    keys.add(participant.name().substring(0, dot));
                }
            }
            keys.add(participant.alias());
        }
        Map<String, CmdbAliasLookup.ResolvedParticipant> byLookupKey = cmdbAliasLookup.resolveAll(keys);

        Map<String, CmdbAliasLookup.ResolvedParticipant> byPlantUmlAlias = new LinkedHashMap<>();
        for (ParsedDiagram.Participant participant : diagram.participants()) {
            CmdbAliasLookup.ResolvedParticipant match = null;
            if (participant.name() != null && !participant.name().isBlank()) {
                match = byLookupKey.get(participant.name());
                if (match == null) {
                    int dot = participant.name().indexOf('.');
                    if (dot > 0) {
                        match = byLookupKey.get(participant.name().substring(0, dot));
                    }
                }
            }
            if (match == null) {
                match = byLookupKey.get(participant.alias());
            }
            if (match != null) {
                byPlantUmlAlias.put(participant.alias(), match);
            }
        }
        return byPlantUmlAlias;
    }

    private List<Call> collectCalls(ParsedDiagram diagram, Map<String, CmdbAliasLookup.ResolvedParticipant> resolved,
            List<ArtifactNotice> notices) {
        List<Call> calls = new ArrayList<>();
        for (ParsedDiagram.Message message : diagram.messages()) {
            String elementRef = message.fromAlias() + "->" + message.toAlias();
            if (message.fromAlias().equals(message.toAlias())) {
                notices.add(excluded(elementRef, message.line(), "self_call"));
                continue;
            }
            Matcher matcher = REST_CALL.matcher(message.label());
            boolean parsed = matcher.find();
            String method = parsed ? matcher.group(1).toUpperCase(Locale.ROOT) : UNKNOWN_TYPE;
            String path = parsed ? matcher.group(2) : truncate(message.label());
            if (!parsed) {
                notices.add(notice(UNKNOWN_REQUEST, "info", Map.of("elementRef", elementRef,
                        "line", message.line(), "path", path)));
            }
            CmdbAliasLookup.ResolvedParticipant receiver = resolved.get(message.toAlias());
            if (receiver != null && receiver.ambiguous()) {
                notices.add(excluded(elementRef, message.line(), "receiver_ambiguous"));
                receiver = null;
            } else if (receiver == null) {
                notices.add(excluded(elementRef, message.line(), "receiver_not_in_cmdb"));
            }
            calls.add(new Call(message, method, path, receiver));
        }
        return calls;
    }

    private List<OperationMatchCandidate> candidatesOf(List<Call> calls) {
        Map<String, OperationMatchCandidate> distinct = new LinkedHashMap<>();
        for (Call call : calls) {
            if (call.receiver() == null || UNKNOWN_TYPE.equals(call.method())) {
                continue;
            }
            distinct.putIfAbsent(matchKey(call.productCode(), call.path(), call.method()),
                    new OperationMatchCandidate(call.path(), call.method(), null, call.productCode()));
        }
        return new ArrayList<>(distinct.values());
    }

    private Map<String, MatchedArchOperation> indexMatches(List<MatchedArchOperation> matches) {
        Map<String, MatchedArchOperation> byKey = new HashMap<>();
        for (MatchedArchOperation match : matches) {
            if (Boolean.TRUE.equals(match.getNotFound()) || match.getName() == null) {
                continue;
            }
            byKey.putIfAbsent(matchKey(match.getProductCode(), match.getName(), match.getType()), match);
        }
        return byKey;
    }

    private void buildSnapshot(E2ESequenceSnapshot snapshot, List<Call> calls,
            Map<String, MatchedArchOperation> matchByKey, List<ArtifactNotice> notices) {
        Map<String, E2ESequenceSnapshot.ProductDraft> products = new LinkedHashMap<>();
        Map<String, E2ESequenceSnapshot.ContainerDraft> containers = new LinkedHashMap<>();
        Map<String, E2ESequenceSnapshot.InterfaceDraft> interfaces = new LinkedHashMap<>();
        Map<String, E2ESequenceSnapshot.OperationDraft> operations = new LinkedHashMap<>();
        Map<String, String> activeOperationByLifeline = new HashMap<>();
        Map<String, Integer> excludedParentLineByLifeline = new HashMap<>();
        Map<String, Integer> callOrderByCaller = new HashMap<>();

        for (Call call : calls) {
            String fromAlias = call.message().fromAlias();
            String toAlias = call.message().toAlias();
            int line = call.message().line();

            if (call.receiver() == null) {
                activeOperationByLifeline.remove(toAlias);
                excludedParentLineByLifeline.put(toAlias, line);
                continue;
            }
            Integer excludedParentLine = excludedParentLineByLifeline.get(fromAlias);
            if (excludedParentLine != null) {
                notices.add(notice(EXCLUDE, "warning", Map.of("elementRef", fromAlias + "->" + toAlias,
                        "line", line, "reason", "parent_excluded", "parentLine", excludedParentLine)));
                activeOperationByLifeline.remove(toAlias);
                excludedParentLineByLifeline.put(toAlias, excludedParentLine);
                continue;
            }

            MatchedArchOperation match = matchByKey.get(matchKey(call.productCode(), call.path(), call.method()));
            boolean matched = match != null && match.getInterfaceObj() != null && match.getInterfaceObj().getCode() != null;

            String productUid = call.productCode();
            String containerUid = productUid;
            String interfaceUid = interfaceCode(call.method(), call.path());
            String operationExtUid = operationUid(productUid, interfaceUid, call.method(), call.path());

            products.computeIfAbsent(productUid, uid -> product(uid, productName(call, match)));
            containers.computeIfAbsent(containerUid, uid -> container(uid, productUid, null));
            interfaces.computeIfAbsent(interfaceUid, uid -> {
                notices.add(implicitCast(uid, "interface", "protocol=UNKNOWN, source=null", "info"));
                return anInterface(uid, containerUid, call.method() + " " + call.path());
            });
            operations.computeIfAbsent(operationExtUid, uid -> {
                if (!matched && !UNKNOWN_TYPE.equals(call.method())) {
                    notices.add(notice(NOT_IN_LANDSCAPE, "warning", Map.of("elementRef", fromAlias + "->" + toAlias,
                            "line", line, "productCode", call.productCode(), "method", call.method(),
                            "path", call.path())));
                }
                notices.add(implicitCast(uid, "operation", "sla=null", "warning"));
                E2ESequenceSnapshot.OperationDraft draft = operation(uid, interfaceUid, call.path(), call.method(),
                        matched ? match.getId() : null);
                draft.setMatchedOperation(matched ? matchedAttributes(match) : null);
                return draft;
            });

            String callerExtUid = activeOperationByLifeline.get(fromAlias);
            String callerKey = callerExtUid == null ? "" : callerExtUid;
            int callOrder = callOrderByCaller.merge(callerKey, 1, Integer::sum) - 1;

            E2ESequenceSnapshot.OperationRelationDraft relation = new E2ESequenceSnapshot.OperationRelationDraft();
            relation.setCallerOperationExtUid(callerExtUid);
            relation.setCalleeOperationExtUid(operationExtUid);
            relation.setCallOrder(callOrder);
            snapshot.getOperationRelations().add(relation);

            activeOperationByLifeline.put(toAlias, operationExtUid);
            excludedParentLineByLifeline.remove(toAlias);
        }

        snapshot.getProducts().addAll(products.values());
        snapshot.getContainers().addAll(containers.values());
        snapshot.getInterfaces().addAll(interfaces.values());
        snapshot.getOperations().addAll(operations.values());
    }

    private String productUid(Call call, MatchedArchOperation match) {
        if (match.getProduct() != null && match.getProduct().getAlias() != null) {
            return match.getProduct().getAlias();
        }
        return call.productCode();
    }

    private String productName(Call call, MatchedArchOperation match) {
        if (match != null && match.getProduct() != null && match.getProduct().getName() != null) {
            return match.getProduct().getName();
        }
        if (call.receiver().kind() == CmdbAliasLookup.ResolvedParticipant.Kind.SYSTEM) {
            return call.receiver().name();
        }
        return null;
    }

    private E2ESequenceSnapshot.ProductDraft product(String uid, String name) {
        E2ESequenceSnapshot.ProductDraft draft = new E2ESequenceSnapshot.ProductDraft();
        draft.setUid(uid);
        draft.setExtUid(uid);
        draft.setName(name != null ? name : uid);
        return draft;
    }

    private E2ESequenceSnapshot.ContainerDraft container(String uid, String productUid, String name) {
        E2ESequenceSnapshot.ContainerDraft draft = new E2ESequenceSnapshot.ContainerDraft();
        draft.setUid(uid);
        draft.setExtUid(uid);
        draft.setProductUid(productUid);
        draft.setName(name != null ? name : uid);
        return draft;
    }

    private E2ESequenceSnapshot.InterfaceDraft anInterface(String uid, String containerUid, String name) {
        E2ESequenceSnapshot.InterfaceDraft draft = new E2ESequenceSnapshot.InterfaceDraft();
        draft.setUid(uid);
        draft.setExtUid(uid);
        draft.setContainerUid(containerUid);
        draft.setName(name != null ? name : uid);
        draft.setProtocol(DEFAULT_PROTOCOL);
        draft.setSource(null);
        return draft;
    }

    private E2ESequenceSnapshot.OperationDraft operation(String extUid, String interfaceUid, String name, String type,
            Integer connectionOperationId) {
        E2ESequenceSnapshot.OperationDraft draft = new E2ESequenceSnapshot.OperationDraft();
        draft.setExtUid(extUid);
        draft.setInterfaceUid(interfaceUid);
        draft.setName(name);
        draft.setType(type);
        draft.setConnectionOperationId(connectionOperationId);
        return draft;
    }

    private E2ESequenceSnapshot.E2eScenarioDraft scenario(String artifactUid, String name, String biStepCode) {
        E2ESequenceSnapshot.E2eScenarioDraft draft = new E2ESequenceSnapshot.E2eScenarioDraft();
        draft.setUid(artifactUid);
        draft.setExtUid(artifactUid);
        draft.setName(name);
        draft.setBiStepUid(biStepCode != null && !biStepCode.isBlank() ? biStepCode : null);
        return draft;
    }

    private E2ESequenceSnapshot.BiStepDraft biStep(String biStepCode) {
        E2ESequenceSnapshot.BiStepDraft draft = new E2ESequenceSnapshot.BiStepDraft();
        draft.setUid(biStepCode);
        draft.setExtUid(biStepCode);
        draft.setName(biStepCode);
        return draft;
    }

    static String interfaceCode(String method, String path) {
        CRC32 crc32 = new CRC32();
        crc32.update((method + path).getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc32.getValue());
    }

    static String truncate(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= MAX_PATH_LENGTH ? trimmed : trimmed.substring(0, MAX_PATH_LENGTH);
    }

    private Map<String, Object> matchedAttributes(MatchedArchOperation match) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("operationId", match.getId());
        attributes.put("name", match.getName());
        attributes.put("type", match.getType());
        if (match.getInterfaceObj() != null) {
            attributes.put("interfaceCode", match.getInterfaceObj().getCode());
            attributes.put("interfaceName", match.getInterfaceObj().getName());
        }
        if (match.getContainer() != null) {
            attributes.put("containerCode", match.getContainer().getCode());
            attributes.put("containerName", match.getContainer().getName());
        }
        if (match.getProduct() != null) {
            attributes.put("productAlias", match.getProduct().getAlias());
            attributes.put("productName", match.getProduct().getName());
        }
        return attributes;
    }

    static String operationUid(String productCode, String interfaceCode, String method, String path) {
        String seed = productCode + " " + interfaceCode + " " + method + " " + path;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, UID_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String matchKey(String productCode, String methodName, String methodType) {
        return String.join("|",
                productCode == null ? "" : productCode.toLowerCase(Locale.ROOT),
                methodName == null ? "" : methodName.toLowerCase(Locale.ROOT),
                methodType == null ? "" : methodType.toUpperCase(Locale.ROOT));
    }

    private ArtifactNotice excluded(String elementRef, int line, String reason) {
        return notice(EXCLUDE, "warning", Map.of("elementRef", elementRef, "line", line, "reason", reason));
    }

    private ArtifactNotice implicitCast(String entityUid, String entityType, String reason, String level) {
        return notice(IMPLICIT_CAST, level, Map.of("entityUid", entityUid, "entityType", entityType, "reason", reason));
    }

    private ArtifactNotice notice(String code, String level, Map<String, Object> details) {
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }
        return new ArtifactNotice(null, null, code, level, "transform", null, null, null, null,
                code, detailsJson, null, null, null, null);
    }
}
