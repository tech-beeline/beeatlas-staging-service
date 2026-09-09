/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.ErrorUml;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.Diagram;
import net.sourceforge.plantuml.error.PSystemError;
import net.sourceforge.plantuml.klimt.creole.Display;
import net.sourceforge.plantuml.sequencediagram.Event;
import net.sourceforge.plantuml.sequencediagram.Message;
import net.sourceforge.plantuml.sequencediagram.Participant;
import net.sourceforge.plantuml.sequencediagram.SequenceDiagram;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses PlantUML source with the {@code net.sourceforge.plantuml} library (the sole parsing
 * engine for this validation contour, per the e2e-import vision §6.3) into {@link ParsedDiagram},
 * or a list of blocking {@link Finding}s if the text isn't valid PlantUML / not a Sequence Diagram.
 */
@Component
public class PlantUmlDiagramParser {

    /** PlantUML's block comment: {@code /' ... '/}, possibly spanning lines. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/'.*?'/", Pattern.DOTALL);

    public ParseOutcome parse(String source) {
        List<BlockUml> blocks;
        try {
            blocks = new SourceStringReader(source).getBlocks();
        } catch (Exception e) {
            return ParseOutcome.failed(List.of(Finding.error(
                    "e2e.validation.syntax.invalid",
                    "Не удалось разобрать текст PlantUML: " + e.getMessage()
                            + ". Проверьте синтаксис диаграммы и исправьте ошибку.",
                    null, null, null)));
        }

        if (blocks.isEmpty()) {
            return ParseOutcome.failed(List.of(Finding.error(
                    "e2e.validation.syntax.invalid",
                    "Не найден блок @startuml/@enduml. Диаграмма должна начинаться со строки @startuml"
                            + " и заканчиваться строкой @enduml.",
                    null, null, null)));
        }

        if (hasEmptyBody(source)) {
            // PlantUML has nothing to infer a type from here, and answers inconsistently (a welcome
            // easter egg for a bare block, a syntax error when the block holds whitespace-only lines).
            // Neither answer is honest for the user: an empty diagram isn't "some other diagram type",
            // it's a diagram with no participants — report it as such (e2e.validation.participants.missing).
            return ParseOutcome.ok(new ParsedDiagram(List.of(), List.of()));
        }

        Diagram diagram = blocks.get(0).getDiagram();

        if (diagram instanceof PSystemError errorDiagram) {
            return ParseOutcome.failed(toSyntaxErrorFindings(errorDiagram));
        }

        if (!(diagram instanceof SequenceDiagram sequenceDiagram)) {
            return ParseOutcome.failed(List.of(Finding.error(
                    "e2e.validation.diagram.not_sequence",
                    "Это не Sequence-диаграмма. Валидатор проверяет только PlantUML Sequence Diagram —"
                            + " используйте participant/actor и стрелки сообщений (->), без rectangle,"
                            + " database и других типов диаграмм.",
                    null, null, null)));
        }

        return ParseOutcome.ok(toParsedDiagram(sequenceDiagram, source));
    }

    /** True when the first @startuml/@enduml block carries nothing but blank lines and comments. */
    private static boolean hasEmptyBody(String source) {
        String withoutBlockComments = BLOCK_COMMENT.matcher(source).replaceAll("");
        boolean insideBlock = false;
        for (String rawLine : withoutBlockComments.split("\\R")) {
            String line = rawLine.trim();
            if (!insideBlock) {
                insideBlock = startsWithIgnoreCase(line, "@startuml");
                continue;
            }
            if (startsWithIgnoreCase(line, "@enduml")) {
                return true;
            }
            if (!line.isEmpty() && !line.startsWith("'")) {
                return false;
            }
        }
        // no @enduml at all — not our case to judge, let PlantUML report the unterminated block
        return false;
    }

    private static boolean startsWithIgnoreCase(String line, String prefix) {
        return line.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private List<Finding> toSyntaxErrorFindings(PSystemError errorDiagram) {
        List<Finding> findings = new ArrayList<>();
        for (ErrorUml error : errorDiagram.getErrorsUml()) {
            Integer line = error.getLineLocation() != null ? error.getLineLocation().getPosition() + 1 : null;
            findings.add(Finding.error("e2e.validation.syntax.invalid",
                    "Ошибка синтаксиса PlantUML: " + error.getError()
                            + ". Исправьте синтаксис в указанной строке и повторите загрузку.",
                    line, line, null));
        }
        if (findings.isEmpty()) {
            findings.add(Finding.error("e2e.validation.syntax.invalid",
                    "Некорректный синтаксис PlantUML. Проверьте текст диаграммы и исправьте ошибки.",
                    null, null, null));
        }
        return findings;
    }

    private ParsedDiagram toParsedDiagram(SequenceDiagram sequenceDiagram, String source) {
        SourceLineLocator locator = new SourceLineLocator(source);

        List<ParsedDiagram.Participant> participants = new ArrayList<>();
        for (Participant participant : sequenceDiagram.participants()) {
            String alias = participant.getCode();
            String name = joinDisplay(participant.getDisplay(false));
            participants.add(new ParsedDiagram.Participant(
                    alias, name, participant.getType().name(), locator.findFirstLine(alias)));
        }

        List<ParsedDiagram.Message> messages = new ArrayList<>();
        int cursorLine = 1;
        for (Event event : sequenceDiagram.events()) {
            if (event instanceof Message message) {
                String fromAlias = message.getParticipant1().getCode();
                String toAlias = message.getParticipant2().getCode();
                int line = locator.findMessageLine(fromAlias, toAlias, cursorLine);
                // advance past this line so the next message of the same pair doesn't collapse onto it
                cursorLine = line + 1;
                messages.add(new ParsedDiagram.Message(fromAlias, toAlias, joinDisplay(message.getLabel()), line));
            }
        }

        return new ParsedDiagram(participants, messages);
    }

    private static String joinDisplay(Display display) {
        if (display == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (CharSequence line : display) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(line);
        }
        return builder.toString().trim();
    }
}
