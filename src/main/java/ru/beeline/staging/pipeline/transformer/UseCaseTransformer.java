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
import ru.beeline.staging.dto.notice.TransformResult;
import ru.beeline.staging.e2e.CmdbAliasLookup;
import ru.beeline.staging.e2e.ParseOutcome;
import ru.beeline.staging.e2e.ParsedDiagram;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.pipeline.StageContext;
import ru.beeline.staging.product.dto.search.MatchedArchOperation;
import ru.beeline.staging.product.dto.search.OperationMatchCandidate;
import ru.beeline.staging.service.RunBranchResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class UseCaseTransformer implements ArtifactTransformer {

    public static final String MODULE_CODE = "usecase-transformer";
    public static final String PARSE_FAILED = "usecase.transform.error.parse_failed";
    public static final String SEARCH_UNAVAILABLE = "usecase.transform.search_matched_unavailable";
    public static final String NOT_IN_LANDSCAPE = "usecase.transform.operation_not_in_landscape";
    public static final String UNKNOWN_REQUEST = "usecase.transform.unknown_request";
    public static final String RECEIVER_NOT_IN_CMDB = "usecase.transform.receiver_not_in_cmdb";

    private static final Pattern REST_CALL = Pattern.compile(
            "(?i)\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(\\S+)");
    private static final Pattern FRAGMENT_START = Pattern.compile(
            "^(alt|else|loop|opt|par|group|critical|break)\\b.*");
    private static final String UNKNOWN_TYPE = "UNKNOWN";
    private static final String DEFAULT_PROTOCOL = "UNKNOWN";
    private static final int MAX_PATH_LENGTH = 255;

    private final PlantUmlDiagramParser parser;
    private final CmdbAliasLookup       cmdbAliasLookup;
    private final ProductServiceClient  productServiceClient;
    private final ObjectMapper          objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() {
        return "Decomposes the UseCase diagram and matches its calls against the fdm-products operations";
    }

    @Override
    public TransformResult transform(String artifactUid, String rawContent, StageContext context) {
        ParseOutcome outcome = parser.parse(rawContent);
        if (!outcome.isParsed()) {
            String reason = outcome.findings().isEmpty() ? "unparseable" : outcome.findings().get(0).message();
            return TransformResult.of(null, List.of(parseFailed(artifactUid, reason)));
        }

        String branch = context.branch() == null || context.branch().isBlank()
                ? RunBranchResolver.DEFAULT_BRANCH : context.branch();
        UseCaseSnapshot snapshot = new UseCaseSnapshot();
        snapshot.setUsecase(header(artifactUid, context));
        snapshot.setBranch(branch);

        ParsedDiagram diagram = outcome.diagram();
        Map<String, CmdbAliasLookup.ResolvedParticipant> resolved = resolveParticipants(diagram);
        List<ArtifactNotice> notices = new ArrayList<>();
        List<Call> calls = collectCalls(diagram, rawContent, resolved, notices);

        Map<String, MatchedArchOperation> matchByKey = Map.of();
        List<OperationMatchCandidate> candidates = candidatesOf(calls);
        if (!candidates.isEmpty()) {
            try {
                matchByKey = indexMatches(productServiceClient.searchMatchedOperations(candidates));
            } catch (RuntimeException e) {
                notices.add(notice(SEARCH_UNAVAILABLE, "error", Map.of("artifactUid", artifactUid,
                        "reason", String.valueOf(e.getMessage()))));
                return TransformResult.of(null, notices);
            }
        }

        build(snapshot, calls, matchByKey, notices);
        log.info("stage=transformer, module={}, uid={}, branch={}, steps={}, matched={}",
                MODULE_CODE, artifactUid, branch, snapshot.getSteps().size(),
                snapshot.getEntities().getOperations().stream()
                        .filter(o -> o.getConnectionOperationId() != null).count());
        return TransformResult.of(snapshot, notices);
    }

    private record Call(ParsedDiagram.Message message, String partId, int seq, String scenarioType, String stepType,
                        String method, String path, String receiverKind,
                        CmdbAliasLookup.ResolvedParticipant receiver) {

        String productCode() {
            return receiver == null ? null
                    : (receiver.productAlias() != null ? receiver.productAlias() : receiver.alias());
        }
    }

    private List<Call> collectCalls(ParsedDiagram diagram, String rawContent,
                                    Map<String, CmdbAliasLookup.ResolvedParticipant> resolved,
                                    List<ArtifactNotice> notices) {
        String[] fragmentByLine = fragmentsByLine(rawContent);
        Map<String, String> kindByAlias = new LinkedHashMap<>();
        diagram.participants().forEach(p -> kindByAlias.put(p.alias(), p.declaredKind()));
        List<Call> calls = new ArrayList<>();
        int seq = 0;
        for (ParsedDiagram.Message message : diagram.messages()) {
            seq++;
            String partId = String.format("P-%02d", seq);
            String fragment = fragmentAt(fragmentByLine, message.line());
            Matcher matcher = REST_CALL.matcher(message.label());
            boolean parsed = matcher.find();
            String method = parsed ? matcher.group(1).toUpperCase(Locale.ROOT) : UNKNOWN_TYPE;
            String path = parsed ? matcher.group(2) : truncate(message.label());
            if (!parsed) {
                notices.add(notice(UNKNOWN_REQUEST, "info", Map.of("partId", partId,
                        "line", message.line(), "path", path)));
            }
            CmdbAliasLookup.ResolvedParticipant receiver = resolved.get(message.toAlias());
            if (receiver != null && receiver.ambiguous()) {
                notices.add(notice(RECEIVER_NOT_IN_CMDB, "warning", Map.of("partId", partId,
                        "line", message.line(), "participant", message.toAlias(), "reason", "receiver_ambiguous")));
                receiver = null;
            } else if (receiver == null) {
                notices.add(notice(RECEIVER_NOT_IN_CMDB, "warning", Map.of("partId", partId,
                        "line", message.line(), "participant", message.toAlias(),
                        "reason", PlantUmlE2eDecomposer.isProduct(kindByAlias.get(message.toAlias()))
                                ? "receiver_not_in_cmdb" : "receiver_is_not_a_product")));
            }
            calls.add(new Call(message, partId, seq, scenarioTypeOf(fragment), stepTypeOf(fragment),
                    method, path, kindByAlias.get(message.toAlias()), receiver));
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
            String requested = match.getRequestedMethodName() != null ? match.getRequestedMethodName() : match.getName();
            byKey.putIfAbsent(matchKey(match.getProductCode(), requested, match.getType()), match);
        }
        return byKey;
    }

    private void build(UseCaseSnapshot snapshot, List<Call> calls, Map<String, MatchedArchOperation> matchByKey,
                       List<ArtifactNotice> notices) {
        E2ESequenceSnapshot entities = snapshot.getEntities();
        Map<String, E2ESequenceSnapshot.ProductDraft> products = new LinkedHashMap<>();
        Map<String, E2ESequenceSnapshot.ContainerDraft> containers = new LinkedHashMap<>();
        Map<String, E2ESequenceSnapshot.InterfaceDraft> interfaces = new LinkedHashMap<>();
        Map<String, E2ESequenceSnapshot.OperationDraft> operations = new LinkedHashMap<>();
        Map<String, String> activeOperationByLifeline = new HashMap<>();

        for (Call call : calls) {
            UseCaseSnapshot.Step step = new UseCaseSnapshot.Step();
            step.setPartId(call.partId());
            step.setSeq(call.seq());
            step.setScenarioType(call.scenarioType());
            step.setStepType(call.stepType());
            step.setName(call.message().label());
            step.setCallerOperationExtUid(activeOperationByLifeline.get(call.message().fromAlias()));
            snapshot.getSteps().add(step);

            if (call.receiver() == null) {
                step.setReason(PlantUmlE2eDecomposer.isProduct(call.receiverKind())
                        ? "Участник '" + call.message().toAlias() + "' не найден в CMDB — "
                                + "сторона вызова не определена"
                        : "Участник '" + call.message().toAlias() + "' объявлен как "
                                + String.valueOf(call.receiverKind()).toLowerCase(Locale.ROOT)
                                + " — это не продукт ландшафта, вызов не выгружается");
                activeOperationByLifeline.remove(call.message().toAlias());
                continue;
            }

            MatchedArchOperation match = matchByKey.get(matchKey(call.productCode(), call.path(), call.method()));
            boolean matched = match != null && match.getInterfaceObj() != null
                    && match.getInterfaceObj().getCode() != null;

            String productUid = call.productCode();
            String containerUid = productUid;
            String interfaceUid = PlantUmlE2eDecomposer.interfaceCode(call.method(), call.path());
            String operationExtUid = PlantUmlE2eDecomposer.operationUid(productUid, interfaceUid,
                    call.method(), call.path());

            products.computeIfAbsent(productUid, uid -> product(uid, productName(call, match)));
            containers.computeIfAbsent(containerUid, uid -> container(uid, productUid));
            interfaces.computeIfAbsent(interfaceUid,
                    uid -> anInterface(uid, containerUid, call.method() + " " + call.path()));
            operations.computeIfAbsent(operationExtUid, uid -> {
                if (!matched && !UNKNOWN_TYPE.equals(call.method())) {
                    notices.add(notice(NOT_IN_LANDSCAPE, "warning", Map.of("partId", call.partId(),
                            "productCode", call.productCode(), "method", call.method(), "path", call.path())));
                }
                return operation(uid, interfaceUid, call.path(), call.method(),
                        matched ? match.getId() : null, matched ? matchedAttributes(match) : null);
            });

            step.setCalleeOperationExtUid(operationExtUid);
            step.setProductAlias(productUid);
            step.setInterfaceCode(interfaceUid);
            step.setOperationType(call.method());
            step.setOperationName(call.path());
            if (!matched) {
                step.setReason(UNKNOWN_TYPE.equals(call.method())
                        ? "В сообщении нет REST-вызова (ожидается METHOD /путь) — архитектурная операция не определена"
                        : "Операция " + call.method() + " " + call.path() + " не найдена в архитектуре продукта "
                                + call.productCode());
            }
            activeOperationByLifeline.put(call.message().toAlias(), operationExtUid);
        }

        entities.getProducts().addAll(products.values());
        entities.getContainers().addAll(containers.values());
        entities.getInterfaces().addAll(interfaces.values());
        entities.getOperations().addAll(operations.values());
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

    private UseCaseSnapshot.Header header(String artifactUid, StageContext context) {
        UseCaseSnapshot.Header header = new UseCaseSnapshot.Header();
        header.setCode(artifactUid);
        header.setName(context.payloadText("name"));
        header.setBiStepCode(blankToNull(context.payloadText("biStepCode")));
        header.setProjectCode(context.payloadText("projectCode"));
        return header;
    }

    private E2ESequenceSnapshot.ProductDraft product(String uid, String name) {
        E2ESequenceSnapshot.ProductDraft draft = new E2ESequenceSnapshot.ProductDraft();
        draft.setUid(uid);
        draft.setExtUid(uid);
        draft.setName(name != null ? name : uid);
        return draft;
    }

    private E2ESequenceSnapshot.ContainerDraft container(String uid, String productUid) {
        E2ESequenceSnapshot.ContainerDraft draft = new E2ESequenceSnapshot.ContainerDraft();
        draft.setUid(uid);
        draft.setExtUid(uid);
        draft.setProductUid(productUid);
        draft.setName(uid);
        return draft;
    }

    private E2ESequenceSnapshot.InterfaceDraft anInterface(String uid, String containerUid, String name) {
        E2ESequenceSnapshot.InterfaceDraft draft = new E2ESequenceSnapshot.InterfaceDraft();
        draft.setUid(uid);
        draft.setExtUid(uid);
        draft.setContainerUid(containerUid);
        draft.setName(name);
        draft.setProtocol(DEFAULT_PROTOCOL);
        return draft;
    }

    private E2ESequenceSnapshot.OperationDraft operation(String extUid, String interfaceUid, String name, String type,
                                                        Integer connectionOperationId,
                                                        Map<String, Object> matchedOperation) {
        E2ESequenceSnapshot.OperationDraft draft = new E2ESequenceSnapshot.OperationDraft();
        draft.setExtUid(extUid);
        draft.setInterfaceUid(interfaceUid);
        draft.setName(name);
        draft.setType(type);
        draft.setConnectionOperationId(connectionOperationId);
        draft.setMatchedOperation(matchedOperation);
        return draft;
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

    private String productName(Call call, MatchedArchOperation match) {
        if (match != null && match.getProduct() != null && match.getProduct().getName() != null) {
            return match.getProduct().getName();
        }
        return call.receiver().kind() == CmdbAliasLookup.ResolvedParticipant.Kind.SYSTEM
                ? call.receiver().name() : null;
    }

    static String[] fragmentsByLine(String text) {
        String[] lines = text.split("\\R", -1);
        String[] fragmentByLine = new String[lines.length + 2];
        Deque<String> open = new ArrayDeque<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim().toLowerCase(Locale.ROOT);
            fragmentByLine[i + 1] = open.peek();
            if ("end".equals(line)) {
                open.poll();
                continue;
            }
            Matcher start = FRAGMENT_START.matcher(line);
            if (start.matches() && !"else".equals(start.group(1))) {
                open.push(start.group(1));
            }
        }
        return fragmentByLine;
    }

    private static String fragmentAt(String[] fragmentByLine, int line) {
        return line > 0 && line < fragmentByLine.length ? fragmentByLine[line] : null;
    }

    private static String scenarioTypeOf(String fragment) {
        if ("alt".equals(fragment)) return "alternative";
        if ("opt".equals(fragment)) return "exception";
        return "main";
    }

    private static String stepTypeOf(String fragment) {
        if ("alt".equals(fragment)) return "condition";
        if ("loop".equals(fragment)) return "loop";
        if ("opt".equals(fragment)) return "exception";
        return "action";
    }

    private static String matchKey(String productCode, String methodName, String methodType) {
        return String.join("|",
                productCode == null ? "" : productCode.toLowerCase(Locale.ROOT),
                methodName == null ? "" : methodName.toLowerCase(Locale.ROOT),
                methodType == null ? "" : methodType.toUpperCase(Locale.ROOT));
    }

    private static String truncate(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= MAX_PATH_LENGTH ? trimmed : trimmed.substring(0, MAX_PATH_LENGTH);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ArtifactNotice notice(String code, String level, Map<String, Object> details) {
        return new ArtifactNotice(null, null, code, level, "transform", null, "usecase_step",
                String.valueOf(details.get("partId")), null, code, json(details), null, null, null, null);
    }

    private ArtifactNotice parseFailed(String artifactUid, String reason) {
        return new ArtifactNotice(null, null, PARSE_FAILED, "error", "transform", null, "usecase", artifactUid, null,
                reason, json(Map.of("reason", reason)), null, null, artifactUid, null);
    }

    private String json(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            return "{}";
        }
    }
}
