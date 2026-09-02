/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.e2e.RecognizedCall;
import ru.beeline.staging.dto.e2e.RecognizedParticipant;
import ru.beeline.staging.dto.e2e.UnrecognizedCall;
import ru.beeline.staging.dto.e2e.UnrecognizedParticipant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for PlantUML e2e Sequence Diagram validation (STG-04/STG-07): parses
 * the text, checks syntax/diagram-type, resolves participants against the CMDB landscape, and
 * checks call messages for a REST endpoint. Pure function of the input text plus the current
 * state of the two lookups — no persistence, no pipeline side effects (STG-01/STG-02).
 */
@Component
@RequiredArgsConstructor
public class PlantUmlValidationEngine {

    private static final Pattern REST_CALL = Pattern.compile(
            "(?i)\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(/\\S+)");

    private final PlantUmlDiagramParser parser;
    private final CmdbAliasLookup cmdbAliasLookup;
    private final RestEndpointLookup restEndpointLookup;

    public EngineResult validate(String plantUmlText) {
        ParseOutcome outcome = parser.parse(plantUmlText);
        if (!outcome.isParsed()) {
            return new EngineResult(List.of(), List.of(), List.of(), List.of(), outcome.findings());
        }

        ParsedDiagram diagram = outcome.diagram();
        List<Finding> findings = new ArrayList<>();

        if (diagram.participants().isEmpty()) {
            findings.add(Finding.error("e2e.validation.participants.missing",
                    "Diagram does not contain any participants", null, null, null));
        }
        if (diagram.messages().isEmpty()) {
            findings.add(Finding.info("e2e.validation.messages.empty",
                    "Diagram does not contain any messages", null, null, null));
        }

        Map<String, CmdbAliasLookup.ResolvedParticipant> resolved = cmdbAliasLookup.resolveAll(collectAliases(diagram));

        List<RecognizedParticipant> recognizedParticipants = new ArrayList<>();
        List<UnrecognizedParticipant> unrecognizedParticipants = new ArrayList<>();
        for (ParsedDiagram.Participant participant : diagram.participants()) {
            CmdbAliasLookup.ResolvedParticipant match = resolved.get(participant.alias());
            if (match != null) {
                recognizedParticipants.add(new RecognizedParticipant(
                        participant.alias(), match.name(), match.kind().name().toLowerCase(Locale.ROOT), participant.line()));
            } else {
                unrecognizedParticipants.add(new UnrecognizedParticipant(participant.alias(), participant.line()));
                findings.add(Finding.warning("e2e.validation.participant.unrecognized",
                        "Participant '" + participant.alias() + "' is not recognized by CMDB alias",
                        participant.line(), participant.line(), participant.alias()));
            }
        }

        List<RecognizedCall> recognizedCalls = new ArrayList<>();
        List<UnrecognizedCall> unrecognizedCalls = new ArrayList<>();
        for (ParsedDiagram.Message message : diagram.messages()) {
            classifyCall(message, recognizedCalls, unrecognizedCalls, findings);
        }

        return new EngineResult(recognizedParticipants, unrecognizedParticipants, recognizedCalls, unrecognizedCalls, findings);
    }

    private void classifyCall(ParsedDiagram.Message message, List<RecognizedCall> recognizedCalls,
                               List<UnrecognizedCall> unrecognizedCalls, List<Finding> findings) {
        String elementRef = message.fromAlias() + "->" + message.toAlias();
        Matcher matcher = REST_CALL.matcher(message.label());
        if (matcher.find()) {
            String method = matcher.group(1).toUpperCase(Locale.ROOT);
            String path = matcher.group(2);
            if (restEndpointLookup.exists(method, path)) {
                recognizedCalls.add(new RecognizedCall(message.fromAlias(), message.toAlias(), method, path, message.line()));
                return;
            }
            findings.add(Finding.warning("e2e.validation.call.no_rest_endpoint",
                    "No matching REST endpoint " + method + " " + path, message.line(), message.line(), elementRef));
        } else {
            findings.add(Finding.warning("e2e.validation.call.no_rest_endpoint",
                    "Message does not declare a REST endpoint (expected 'METHOD /path')",
                    message.line(), message.line(), elementRef));
        }
        unrecognizedCalls.add(new UnrecognizedCall(message.fromAlias(), message.toAlias(), message.label(), message.line()));
    }

    private static Set<String> collectAliases(ParsedDiagram diagram) {
        Set<String> aliases = new LinkedHashSet<>();
        for (ParsedDiagram.Participant participant : diagram.participants()) {
            aliases.add(participant.alias());
        }
        return aliases;
    }
}
