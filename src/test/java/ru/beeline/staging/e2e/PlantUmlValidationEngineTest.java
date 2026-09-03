package ru.beeline.staging.e2e;

import org.junit.jupiter.api.Test;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant.Kind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlantUmlValidationEngineTest {

    private final PlantUmlDiagramParser parser = new PlantUmlDiagramParser();

    @Test
    void reportsValidWhenAllParticipantsAndCallsAreRecognized() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(eq(Set.of("CRM", "crm", "BILLING", "billing"))))
                .thenReturn(Map.of(
                        "CRM", new ResolvedParticipant("crm", "CRM System", Kind.SYSTEM),
                        "BILLING", new ResolvedParticipant("billing", "Billing System", Kind.SYSTEM)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("valid.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.recognizedParticipants()).hasSize(2);
        assertThat(result.unrecognizedParticipants()).isEmpty();
        assertThat(result.recognizedCalls()).hasSize(2);
        assertThat(result.unrecognizedCalls()).isEmpty();
    }

    @Test
    void resolvesParticipantByCmdbMnemonicInTheNamePositionNotOnlyByTheShortAlias() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "CRM", new ResolvedParticipant("crm", "CRM System", Kind.SYSTEM)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("valid.puml"));

        assertThat(result.recognizedParticipants())
                .extracting(rp -> rp.alias())
                .contains("crm");
    }

    @Test
    void flagsUnrecognizedParticipantsAsWarningNotBlockingValidity() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("valid.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.unrecognizedParticipants()).hasSize(2);
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e.validation.participant.unrecognized");
    }

    @Test
    void flagsCallsWithoutMatchingRestEndpointAsUnrecognized() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "CRM", new ResolvedParticipant("crm", "CRM System", Kind.SYSTEM),
                "BILLING", new ResolvedParticipant("billing", "Billing System", Kind.SYSTEM)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(false);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("valid.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.recognizedCalls()).isEmpty();
        assertThat(result.unrecognizedCalls()).hasSize(2);
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e.validation.call.no_rest_endpoint");
    }

    @Test
    void doesNotCountAnEndpointThatExistsOnlyOnAnotherParticipant() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "CRM", new ResolvedParticipant("crm", "CRM System", Kind.SYSTEM),
                "BILLING", new ResolvedParticipant("billing", "Billing System", Kind.SYSTEM)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        // the endpoint exists somewhere, but never on the receiver actually addressed in the diagram
        when(restEndpointLookup.exists(eq("billing"), anyString(), anyString())).thenReturn(false);
        when(restEndpointLookup.exists(eq("crm"), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("valid.puml"));

        // POST /api/v1/order goes crm -> billing, so it must be checked against "billing", not "crm"
        assertThat(result.unrecognizedCalls())
                .extracting(uc -> uc.toAlias())
                .contains("billing");
    }

    @Test
    void recognizesAMethodFollowedByAPathWithoutALeadingSlashAsAnAttemptedRestCall() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "CRM", new ResolvedParticipant("crm", "CRM System", Kind.SYSTEM),
                "BILLING", new ResolvedParticipant("billing", "Billing System", Kind.SYSTEM)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(false);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("no_slash_call.puml"));

        // "POST tratata" must be parsed as method=POST path=tratata, not fall into the generic
        // "no endpoint declared" bucket
        assertThat(result.findings())
                .extracting(Finding::message)
                .anyMatch(message -> message.contains("No matching REST endpoint POST tratata"));
    }

    @Test
    void rejectsDiagramsWithTooManyParticipantsBeforeQueryingCmdb() {
        List<ParsedDiagram.Participant> tooMany = new ArrayList<>();
        for (int i = 0; i < 301; i++) {
            tooMany.add(new ParsedDiagram.Participant("p" + i, "P" + i, "PARTICIPANT", 1));
        }
        PlantUmlDiagramParser stubParser = mock(PlantUmlDiagramParser.class);
        when(stubParser.parse(anyString())).thenReturn(ParseOutcome.ok(new ParsedDiagram(tooMany, List.of())));
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(stubParser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate("@startuml\n@enduml\n");

        assertThat(result.valid()).isFalse();
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e.validation.diagram.too_many_participants");
        org.mockito.Mockito.verifyNoInteractions(cmdbAliasLookup);
    }

    @Test
    void diagramWithoutParticipantsIsInvalid() {
        // A Sequence Diagram with zero participants isn't producible through real PlantUML text
        // (any construct that commits the parser to the Sequence type also creates a participant),
        // so this rule is exercised against a stubbed parse outcome instead of a .puml fixture.
        PlantUmlDiagramParser stubParser = mock(PlantUmlDiagramParser.class);
        when(stubParser.parse(anyString())).thenReturn(ParseOutcome.ok(new ParsedDiagram(List.of(), List.of())));
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(stubParser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate("@startuml\n@enduml\n");

        assertThat(result.valid()).isFalse();
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e.validation.participants.missing");
    }

    @Test
    void validationIsDeterministicForTheSameTextAndLookupState() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "crm", new ResolvedParticipant("crm", "CRM System", Kind.SYSTEM)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        String text = fixture("valid.puml");

        EngineResult first = engine.validate(text);
        EngineResult second = engine.validate(text);

        assertThat(first).isEqualTo(second);
    }

    private static String fixture(String name) {
        try (InputStream in = PlantUmlValidationEngineTest.class.getResourceAsStream("/e2e/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
