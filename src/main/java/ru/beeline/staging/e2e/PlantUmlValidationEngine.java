/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.e2e.RecognizedCall;
import ru.beeline.staging.dto.e2e.RecognizedParticipant;
import ru.beeline.staging.dto.e2e.UnrecognizedCall;
import ru.beeline.staging.dto.e2e.UnrecognizedParticipant;

import java.util.ArrayList;
import java.util.HashMap;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class PlantUmlValidationEngine {

    private static final Pattern REST_CALL = Pattern.compile(
            "(?i)\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(\\S+)");

    /** Above this, a single CMDB batch lookup risks blowing the URL-length limit on fdm-products. */
    private static final int MAX_PARTICIPANTS = 300;

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
                    "В диаграмме нет ни одного участника. Добавьте участников через 'participant Имя'"
                            + " и сообщения между ними.",
                    null, null, null));
        }
        if (diagram.messages().isEmpty()) {
            findings.add(Finding.info("e2e.validation.messages.empty",
                    "В диаграмме нет ни одного сообщения между участниками. Добавьте вызовы вида"
                            + " 'УчастникА -> УчастникБ: METHOD /путь'.",
                    null, null, null));
        }

        if (diagram.participants().size() > MAX_PARTICIPANTS) {
            findings.add(Finding.error("e2e.validation.diagram.too_many_participants",
                    "В диаграмме " + diagram.participants().size() + " участников — это больше лимита в "
                            + MAX_PARTICIPANTS + ". Разбейте диаграмму на несколько более мелких сценариев.",
                    null, null, null));
            return new EngineResult(List.of(), List.of(), List.of(), List.of(), findings);
        }

        Map<String, CmdbAliasLookup.ResolvedParticipant> resolved = cmdbAliasLookup.resolveAll(collectLookupKeys(diagram));

        List<RecognizedParticipant> recognizedParticipants = new ArrayList<>();
        List<UnrecognizedParticipant> unrecognizedParticipants = new ArrayList<>();
        Map<String, CmdbAliasLookup.ResolvedParticipant> resolvedByPlantUmlAlias = new HashMap<>();
        for (ParsedDiagram.Participant participant : diagram.participants()) {
            // process owners write the CMDB mnemonic in the participant name, not the short "as" alias
            CmdbAliasLookup.ResolvedParticipant match = hasText(participant.name()) ? resolved.get(participant.name()) : null;
            if (match == null) {
                // the name itself is often "<CMDB container code>.<qualifier>" (e.g.
                // ext_DynamicSIM.ActivationPageService) — the container is registered under just the
                // prefix, the qualifier is the diagram author's own detail, not part of the CMDB code
                String prefix = mnemonicPrefix(participant.name());
                if (prefix != null) {
                    match = resolved.get(prefix);
                }
            }
            if (match == null) {
                match = resolved.get(participant.alias());
            }
            if (match != null) {
                resolvedByPlantUmlAlias.put(participant.alias(), match);
                recognizedParticipants.add(new RecognizedParticipant(
                        participant.alias(), match.name(), match.kind().name().toLowerCase(Locale.ROOT), participant.line()));
            } else {
                unrecognizedParticipants.add(new UnrecognizedParticipant(participant.alias(), participant.line()));
                findings.add(Finding.warning("e2e.validation.participant.unrecognized",
                        "Участник '" + participant.alias() + "' не найден в CMDB. Проверьте мнемонику:"
                                + " имя перед 'as' (или сам alias, если 'as' не используется) должно точно"
                                + " совпадать с alias/кодом системы или контейнера в CMDB — сверьтесь с BeeAtlas.",
                        participant.line(), participant.line(), participant.alias()));
            }
        }

        List<RecognizedCall> recognizedCalls = new ArrayList<>();
        List<UnrecognizedCall> unrecognizedCalls = new ArrayList<>();
        for (ParsedDiagram.Message message : diagram.messages()) {
            classifyCall(message, resolvedByPlantUmlAlias, recognizedCalls, unrecognizedCalls, findings);
        }

        return new EngineResult(recognizedParticipants, unrecognizedParticipants, recognizedCalls, unrecognizedCalls, findings);
    }

    private void classifyCall(ParsedDiagram.Message message, Map<String, CmdbAliasLookup.ResolvedParticipant> resolvedByPlantUmlAlias,
                               List<RecognizedCall> recognizedCalls, List<UnrecognizedCall> unrecognizedCalls, List<Finding> findings) {
        String elementRef = message.fromAlias() + "->" + message.toAlias();
        Matcher matcher = REST_CALL.matcher(message.label());
        if (!matcher.find()) {
            findings.add(Finding.warning("e2e.validation.call.no_rest_endpoint",
                    "В сообщении не указан REST-вызов (ожидается формат 'МЕТОД /путь', например"
                            + " 'GET /api/v1/order'). Если это не REST-вызов, а обычный текст — предупреждение"
                            + " можно игнорировать.",
                    message.line(), message.line(), elementRef));
            unrecognizedCalls.add(new UnrecognizedCall(message.fromAlias(), message.toAlias(), message.label(), message.line()));
            return;
        }

        String method = matcher.group(1).toUpperCase(Locale.ROOT);
        String path = matcher.group(2);

        // the endpoint must exist on the receiver specifically — existing anywhere in the CMDB is not enough
        CmdbAliasLookup.ResolvedParticipant receiver = resolvedByPlantUmlAlias.get(message.toAlias());
        if (receiver == null) {
            findings.add(Finding.warning("e2e.validation.call.no_rest_endpoint",
                    "Не удалось проверить эндпоинт " + method + " " + path + ": получатель '" + message.toAlias()
                            + "' не найден в CMDB. Сначала исправьте мнемонику участника '" + message.toAlias()
                            + "' (см. предупреждение выше) — тогда эндпоинт будет проверен.",
                    message.line(), message.line(), elementRef));
            unrecognizedCalls.add(new UnrecognizedCall(message.fromAlias(), message.toAlias(), message.label(), message.line()));
            return;
        }

        boolean found;
        try {
            found = restEndpointLookup.exists(receiver.alias(), receiver.name(), method, path);
        } catch (RuntimeException e) {
            // a broken lookup for one call must not take the whole report down (STG-08 guards the
            // input, not a flaky/broken downstream call) — log it and report just this call as
            // unverifiable, the rest of the diagram is still worth validating
            log.warn("REST endpoint check failed for {} {} on '{}': {}", method, path, message.toAlias(), e.toString());
            findings.add(Finding.warning("e2e.validation.call.check_failed",
                    "Не удалось проверить эндпоинт " + method + " " + path + " у '" + message.toAlias()
                            + "': сбой при обращении к CMDB. Повторите валидацию позже; если ошибка повторяется —"
                            + " обратитесь в поддержку BeeAtlas.",
                    message.line(), message.line(), elementRef));
            unrecognizedCalls.add(new UnrecognizedCall(message.fromAlias(), message.toAlias(), message.label(), message.line()));
            return;
        }

        if (found) {
            recognizedCalls.add(new RecognizedCall(message.fromAlias(), message.toAlias(), method, path, message.line()));
            return;
        }
        findings.add(Finding.warning("e2e.validation.call.no_rest_endpoint",
                "Эндпоинт " + method + " " + path + " не найден у '" + message.toAlias() + "' в CMDB. Проверьте"
                        + " метод и путь (регистр, слэши) или убедитесь, что операция вообще зарегистрирована"
                        + " в CMDB у этой системы/контейнера.",
                message.line(), message.line(), elementRef));
        unrecognizedCalls.add(new UnrecognizedCall(message.fromAlias(), message.toAlias(), message.label(), message.line()));
    }

    private static Set<String> collectLookupKeys(ParsedDiagram diagram) {
        Set<String> keys = new LinkedHashSet<>();
        for (ParsedDiagram.Participant participant : diagram.participants()) {
            if (hasText(participant.name())) {
                keys.add(participant.name());
                String prefix = mnemonicPrefix(participant.name());
                if (prefix != null) {
                    keys.add(prefix);
                }
            }
            keys.add(participant.alias());
        }
        return keys;
    }

    /** The part of a dotted mnemonic before the first '.', or null if there's no dot to split on. */
    private static String mnemonicPrefix(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
