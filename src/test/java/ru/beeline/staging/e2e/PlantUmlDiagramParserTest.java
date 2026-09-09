package ru.beeline.staging.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PlantUmlDiagramParserTest {

    private final PlantUmlDiagramParser parser = new PlantUmlDiagramParser();

    @Test
    void parsesParticipantsAndMessagesFromTheUniversalFixture() {
        ParseOutcome outcome = parser.parse(fixture("universal.puml"));

        assertThat(outcome.isParsed()).isTrue();
        assertThat(outcome.diagram().participants())
                .extracting(ParsedDiagram.Participant::alias)
                .containsExactlyInAnyOrder("Client", "BNPL", "Antispam", "AITool", "ARFix");
        assertThat(outcome.diagram().participants())
                .filteredOn(p -> p.alias().equals("BNPL"))
                .extracting(ParsedDiagram.Participant::name)
                .containsExactly("b2c-digital-payments-bnpl");
        assertThat(outcome.diagram().messages()).hasSize(11);
        assertThat(outcome.diagram().messages().get(0).label()).contains("POST /command/createApplication");
        assertThat(outcome.diagram().messages().get(0).fromAlias()).isEqualTo("Client");
        assertThat(outcome.diagram().messages().get(0).toAlias()).isEqualTo("BNPL");
    }

    @Test
    void locatesTheLineOfABackwardArrowMessageInsteadOfFallingBackToAnotherLine() {
        ParseOutcome outcome = parser.parse(fixture("universal.puml"));

        // "BNPL <-- Antispam" (line 22) is a backward arrow — it must not collapse onto line 1
        // (the @startuml fallback) or borrow another message's line
        assertThat(outcome.diagram().messages())
                .filteredOn(m -> m.fromAlias().equals("Antispam") && m.toAlias().equals("BNPL"))
                .extracting(ParsedDiagram.Message::line)
                .containsExactly(22);
    }

    @Test
    void assignsDistinctIncreasingLinesToConsecutiveMessagesOfTheSamePair() {
        ParseOutcome outcome = parser.parse(fixture("universal.puml"));

        // BNPL -> Antispam appears twice in a row (lines 19 and 20) — both must keep their own line
        assertThat(outcome.diagram().messages())
                .filteredOn(m -> m.fromAlias().equals("BNPL") && m.toAlias().equals("Antispam"))
                .extracting(ParsedDiagram.Message::line)
                .containsExactly(19, 20);
    }

    @Test
    void rejectsDiagramThatIsNotASequenceDiagram() {
        ParseOutcome outcome = parser.parse(fixture("not_sequence.puml"));

        assertThat(outcome.isParsed()).isFalse();
        assertThat(outcome.findings())
                .extracting(Finding::code)
                .containsExactly("e2e.validation.diagram.not_sequence");
        assertThat(outcome.findings().get(0).level()).isEqualTo(Finding.Level.ERROR);
    }

    @Test
    void treatsAnEmptyBlockAsAParsedDiagramWithNoParticipants() {
        // PlantUML itself resolves "@startuml\n@enduml" to its welcome easter egg, which is neither an
        // error nor a SequenceDiagram — reporting that as not_sequence gave the user the wrong reason
        ParseOutcome outcome = parser.parse(fixture("empty_body.puml"));

        assertThat(outcome.isParsed()).isTrue();
        assertThat(outcome.diagram().participants()).isEmpty();
        assertThat(outcome.diagram().messages()).isEmpty();
    }

    @Test
    void treatsABlockHoldingOnlyCommentsAndBlankLinesAsEmpty() {
        ParseOutcome outcome = parser.parse("@startuml\n\n' a line comment\n   \n/' a block\n comment '/\n@enduml\n");

        assertThat(outcome.isParsed()).isTrue();
        assertThat(outcome.diagram().participants()).isEmpty();
    }

    @Test
    void stillRejectsANonEmptyBlockThatIsNotASequenceDiagram() {
        // the empty-body shortcut must not swallow real content: "title" alone parses as a ClassDiagram
        ParseOutcome outcome = parser.parse("@startuml\ntitle Hello\n@enduml\n");

        assertThat(outcome.isParsed()).isFalse();
        assertThat(outcome.findings())
                .extracting(Finding::code)
                .containsExactly("e2e.validation.diagram.not_sequence");
    }

    @Test
    void rejectsInvalidPlantUmlSyntax() {
        ParseOutcome outcome = parser.parse(fixture("syntax_error.puml"));

        assertThat(outcome.isParsed()).isFalse();
        assertThat(outcome.findings()).isNotEmpty();
        assertThat(outcome.findings().get(0).code()).isEqualTo("e2e.validation.syntax.invalid");
        assertThat(outcome.findings().get(0).level()).isEqualTo(Finding.Level.ERROR);
    }

    @Test
    void parsesDiagramWithParticipantsButNoMessages() {
        ParseOutcome outcome = parser.parse(fixture("messages_empty.puml"));

        assertThat(outcome.isParsed()).isTrue();
        assertThat(outcome.diagram().participants()).hasSize(2);
        assertThat(outcome.diagram().messages()).isEmpty();
    }

    private static String fixture(String name) {
        try (InputStream in = PlantUmlDiagramParserTest.class.getResourceAsStream("/e2e/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
