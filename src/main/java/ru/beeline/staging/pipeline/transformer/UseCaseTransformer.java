/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.TransformResult;
import ru.beeline.staging.dto.usecase.UseCaseDraft;
import ru.beeline.staging.e2e.ParseOutcome;
import ru.beeline.staging.e2e.ParsedDiagram;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.pipeline.StageContext;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeOperation;
import ru.beeline.staging.service.RunBranchResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class UseCaseTransformer implements ArtifactTransformer {

    public static final String MODULE_CODE = "usecase-transformer";
    public static final String PARSE_FAILED = "usecase.transform.error.parse_failed";

    private static final Pattern REST_CALL = Pattern.compile(
            "(?i)\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(\\S+)");
    private static final Pattern FRAGMENT_START = Pattern.compile(
            "^(alt|else|loop|opt|par|group|critical|break)\\b.*");
    private static final String INTERACTION = "interaction";
    private static final String CALLEE_SIDE = "callee";
    private static final String SUGGESTION = "map_existing | create_new";

    private final PlantUmlDiagramParser parser;
    private final UseCaseLandscapeRepository landscapeRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Maps the UseCase diagram onto the branch landscape and builds the review draft"; }

    @Override
    public TransformResult transform(String artifactUid, String rawContent, StageContext context) {
        ParseOutcome outcome = parser.parse(rawContent);
        if (!outcome.isParsed()) {
            String reason = outcome.findings().isEmpty() ? "unparseable" : outcome.findings().get(0).message();
            return TransformResult.of(null, List.of(parseFailed(artifactUid, reason)));
        }

        String branch = context.branch() == null || context.branch().isBlank()
                ? RunBranchResolver.DEFAULT_BRANCH : context.branch();
        ParsedDiagram diagram = outcome.diagram();
        Map<String, ParsedDiagram.Participant> participants = new HashMap<>();
        diagram.participants().forEach(participant -> participants.putIfAbsent(participant.alias(), participant));
        String[] fragmentByLine = fragmentsByLine(rawContent);

        List<UseCaseDraft.MappedPart> mapped = new ArrayList<>();
        List<UseCaseDraft.UnmappedPart> unmapped = new ArrayList<>();
        Map<String, LandscapeOperation> activeOperationByLifeline = new HashMap<>();

        int seq = 0;
        for (ParsedDiagram.Message message : diagram.messages()) {
            seq++;
            String partId = String.format("P-%02d", seq);
            String fragment = fragmentAt(fragmentByLine, message.line());
            String scenarioType = scenarioTypeOf(fragment);
            String stepType = stepTypeOf(fragment);
            ParsedDiagram.Participant receiver = participants.get(message.toAlias());

            Matcher call = REST_CALL.matcher(message.label());
            boolean hasCall = call.find();
            String method = hasCall ? call.group(1).toUpperCase(Locale.ROOT) : null;
            String path = hasCall ? call.group(2) : null;
            Optional<LandscapeOperation> callee = hasCall
                    ? landscapeRepository.findOperationByCall(path, method, branch,
                            message.toAlias(), mnemonicOf(receiver, message.toAlias()))
                    : Optional.empty();

            if (callee.isPresent()) {
                LandscapeOperation operation = callee.get();
                LandscapeOperation caller = activeOperationByLifeline.get(message.fromAlias());
                mapped.add(new UseCaseDraft.MappedPart(partId, INTERACTION, seq, scenarioType, stepType,
                        message.label(), operation.productCode(), operation.containerCode(),
                        operation.interfaceCode(), operation.operation(), sideOf(caller),
                        null, null, null, UseCaseDraft.CALL_STATUS_CONFIRMED));
                activeOperationByLifeline.put(message.toAlias(), operation);
            } else {
                String reason = hasCall
                        ? "Операция " + method + " " + path + " не найдена у участника '" + message.toAlias()
                                + "' в ландшафте ветки " + branch
                        : "В сообщении нет REST-вызова (ожидается METHOD /путь)";
                unmapped.add(new UseCaseDraft.UnmappedPart(partId, INTERACTION, seq, scenarioType, stepType,
                        message.label(), CALLEE_SIDE, List.of(displayNameOf(receiver, message.toAlias())),
                        reason, SUGGESTION));
            }
        }

        UseCaseDraft draft = new UseCaseDraft(
                new UseCaseDraft.Header(artifactUid, context.payloadText("name"),
                        blankToNull(context.payloadText("biStepCode")), context.payloadText("projectCode")),
                branch, mapped, unmapped);
        log.info("stage=transformer, module={}, uid={}, branch={}, mapped={}, unmapped={}",
                MODULE_CODE, artifactUid, branch, mapped.size(), unmapped.size());
        return TransformResult.of(draft, List.of());
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

    private static UseCaseDraft.Side sideOf(LandscapeOperation operation) {
        return operation == null ? null : new UseCaseDraft.Side(operation.productCode(), operation.containerCode(),
                operation.interfaceCode(), operation.operation());
    }

    private static String mnemonicOf(ParsedDiagram.Participant participant, String alias) {
        String name = participant != null ? participant.name() : null;
        if (name == null || name.isBlank()) {
            return alias;
        }
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String displayNameOf(ParsedDiagram.Participant participant, String alias) {
        return participant != null && participant.name() != null && !participant.name().isBlank()
                ? participant.name() : alias;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ArtifactNotice parseFailed(String artifactUid, String reason) {
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(Map.of("reason", reason));
        } catch (Exception e) {
            detailsJson = "{}";
        }
        return new ArtifactNotice(null, null, PARSE_FAILED, "error", "transform", null, "usecase", artifactUid, null,
                reason, detailsJson, null, null, artifactUid, null);
    }
}
