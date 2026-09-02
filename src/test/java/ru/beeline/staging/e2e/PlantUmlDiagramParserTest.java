package ru.beeline.staging.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PlantUmlDiagramParserTest {

    private final PlantUmlDiagramParser parser = new PlantUmlDiagramParser();

    @Test
    void parsesParticipantsAndMessagesFromValidSequenceDiagram() {
        ParseOutcome outcome = parser.parse(fixture("valid.puml"));

        assertThat(outcome.isParsed()).isTrue();
        assertThat(outcome.diagram().participants())
                .extracting(ParsedDiagram.Participant::alias)
                .containsExactlyInAnyOrder("crm", "billing");
        assertThat(outcome.diagram().messages()).hasSize(2);
        assertThat(outcome.diagram().messages().get(0).label()).contains("POST /api/v1/order");
        assertThat(outcome.diagram().messages().get(0).fromAlias()).isEqualTo("crm");
        assertThat(outcome.diagram().messages().get(0).toAlias()).isEqualTo("billing");
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
