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

/**
 * Parses PlantUML source with the {@code net.sourceforge.plantuml} library (the sole parsing
 * engine for this validation contour, per the e2e-import vision §6.3) into {@link ParsedDiagram},
 * or a list of blocking {@link Finding}s if the text isn't valid PlantUML / not a Sequence Diagram.
 */
@Component
public class PlantUmlDiagramParser {

    public ParseOutcome parse(String source) {
        List<BlockUml> blocks;
        try {
            blocks = new SourceStringReader(source).getBlocks();
        } catch (Exception e) {
            return ParseOutcome.failed(List.of(Finding.error(
                    "e2e.validation.syntax.invalid", "PlantUML text could not be parsed: " + e.getMessage(),
                    null, null, null)));
        }

        if (blocks.isEmpty()) {
            return ParseOutcome.failed(List.of(Finding.error(
                    "e2e.validation.syntax.invalid", "No @startuml/@enduml block found", null, null, null)));
        }

        Diagram diagram = blocks.get(0).getDiagram();

        if (diagram instanceof PSystemError errorDiagram) {
            return ParseOutcome.failed(toSyntaxErrorFindings(errorDiagram));
        }

        if (!(diagram instanceof SequenceDiagram sequenceDiagram)) {
            return ParseOutcome.failed(List.of(Finding.error(
                    "e2e.validation.diagram.not_sequence",
                    "Diagram is not a Sequence Diagram", null, null, null)));
        }

        return ParseOutcome.ok(toParsedDiagram(sequenceDiagram, source));
    }

    private List<Finding> toSyntaxErrorFindings(PSystemError errorDiagram) {
        List<Finding> findings = new ArrayList<>();
        for (ErrorUml error : errorDiagram.getErrorsUml()) {
            Integer line = error.getLineLocation() != null ? error.getLineLocation().getPosition() + 1 : null;
            findings.add(Finding.error("e2e.validation.syntax.invalid", error.getError(), line, line, null));
        }
        if (findings.isEmpty()) {
            findings.add(Finding.error("e2e.validation.syntax.invalid", "Invalid PlantUML syntax", null, null, null));
        }
        return findings;
    }

    private ParsedDiagram toParsedDiagram(SequenceDiagram sequenceDiagram, String source) {
        SourceLineLocator locator = new SourceLineLocator(source);

        List<ParsedDiagram.Participant> participants = new ArrayList<>();
        for (Participant participant : sequenceDiagram.participants()) {
            String alias = participant.getCode();
            participants.add(new ParsedDiagram.Participant(
                    alias, participant.getType().name(), locator.findFirstLine(alias)));
        }

        List<ParsedDiagram.Message> messages = new ArrayList<>();
        int cursorLine = 1;
        for (Event event : sequenceDiagram.events()) {
            if (event instanceof Message message) {
                String fromAlias = message.getParticipant1().getCode();
                String toAlias = message.getParticipant2().getCode();
                int line = locator.findMessageLine(fromAlias, toAlias, cursorLine);
                cursorLine = line;
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
