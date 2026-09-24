package ru.beeline.staging.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.dto.e2e.RecognizedParticipant;
import ru.beeline.staging.dto.e2e.UnrecognizedCall;
import ru.beeline.staging.dto.e2e.UnrecognizedParticipant;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant.Kind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlantUmlValidationEngineTest {

    private static final String DIAGRAM_WITH_DATABASE = """
            @startuml
            actor "Пользователь" as User
            participant "api_gateway" as GW
            database "database" as DB
            User -> GW: GET /api/v1/search
            GW -> DB: SELECT * FROM capability
            @enduml
            """;

    private final PlantUmlDiagramParser parser = new PlantUmlDiagramParser();

    @Test
    void reportsValidWhenAllRealParticipantsAreRecognizedAndEveryLookupIsPermissive() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.recognizedParticipants()).hasSize(4);
        assertThat(result.unrecognizedParticipants()).isEmpty();
        assertThat(result.recognizedCalls()).hasSize(7);
        assertThat(result.unrecognizedCalls()).hasSize(4);
    }

    @Test
    void resolvesParticipantByCmdbMnemonicInTheNamePositionNotOnlyByTheShortAlias() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "b2c-digital-payments-bnpl", new ResolvedParticipant(
                        "b2c-digital-payments-bnpl", "b2c-digital-payments-bnpl", Kind.SYSTEM)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.recognizedParticipants())
                .extracting(RecognizedParticipant::alias)
                .contains("BNPL");
    }

    @Test
    void resolvesAMnemonicByThePrefixBeforeTheFirstDotWhenTheFullNameIsntRegistered() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "ext_Example", new ResolvedParticipant("ext_Example", "Example System", Kind.CONTAINER)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(DOTTED_MNEMONIC_PUML);

        assertThat(result.recognizedParticipants())
                .anySatisfy(rp -> {
                    assertThat(rp.alias()).isEqualTo("Short");
                    assertThat(rp.name()).isEqualTo("Example System");
                    assertThat(rp.kind()).isEqualTo("container");
                });
    }

    @Test
    void recognizesOnlyTheCallsThatReallyExistOnTheRealDevCmdbData() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(eq("b2c-digital-payments-bnpl"), eq("POST"), eq("/command/createApplication")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("antispam"), eq("GET"), eq("/api/v1/calls/")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("antispam"), eq("POST"), eq("/api/v1/calls/feedback")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("ai-tool"), eq("POST"), eq("/chat/completions")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("b2c-digital-payments-bnpl"), eq("POST"), eq("/command/completePayment")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("arfix"), eq("GET"), eq("/api/v1/payment/12345/paymentItem")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("arfix"), eq("POST"), eq("reconciliation-note")))
                .thenReturn(false);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.recognizedCalls()).hasSize(6);
        assertThat(result.recognizedCalls())
                .extracting(call -> call.httpMethod() + " " + call.path())
                .containsExactlyInAnyOrder(
                        "POST /command/createApplication",
                        "GET /api/v1/calls/",
                        "POST /api/v1/calls/feedback",
                        "POST /chat/completions",
                        "POST /command/completePayment",
                        "GET /api/v1/payment/12345/paymentItem");
        assertThat(result.unrecognizedCalls())
                .extracting(UnrecognizedCall::label)
                .contains("POST reconciliation-note");
    }

    @Test
    @DisplayName("Актор не участвует в проверке участников и не роняет валидацию")
    void ignoresActors() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.unrecognizedParticipants()).isEmpty();
        assertThat(result.findings()).extracting(Finding::code)
                .doesNotContain("e2e.validation.participant.unrecognized");
    }

    @Test
    @DisplayName("Участник-БД не проверяется на продукт и не роняет валидацию")
    void ignoresDatabaseParticipants() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "api_gateway", new ResolvedParticipant("api_gateway", "API Gateway", Kind.SYSTEM, "api_gateway")));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(DIAGRAM_WITH_DATABASE);

        assertThat(result.unrecognizedParticipants()).extracting(UnrecognizedParticipant::alias)
                .doesNotContain("DB");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void failsValidationWhenAParticipantIsNotInCmdb() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.valid()).isFalse();
        assertThat(result.unrecognizedParticipants()).hasSize(4);
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e.validation.participant.unrecognized");
    }

    @Test
    void flagsCallsWithoutMatchingRestEndpointAsUnrecognized() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(false);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.recognizedCalls()).isEmpty();
        assertThat(result.unrecognizedCalls()).hasSize(11);
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e.validation.call.no_rest_endpoint");
    }

    @Test
    void aBrokenEndpointCheckDoesNotTakeDownTheWholeReport() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(eq("arfix"), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));
        when(restEndpointLookup.exists(eq("antispam"), anyString(), anyString()))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("ai-tool"), anyString(), anyString()))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("b2c-digital-payments-bnpl"), anyString(), anyString()))
                .thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings())
                .filteredOn(f -> f.code().equals("e2e.validation.call.check_failed"))
                .hasSize(2);
    }

    @Test
    void doesNotCountAnEndpointThatExistsOnlyOnAnotherParticipant() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(eq("antispam"), eq("GET"), eq("/api/v1/calls/")))
                .thenReturn(false);
        when(restEndpointLookup.exists(eq("ai-tool"), eq("GET"), eq("/api/v1/calls/")))
                .thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.unrecognizedCalls())
                .extracting(UnrecognizedCall::toAlias)
                .contains("Antispam");
    }

    @Test
    void recognizesAMethodFollowedByAPathWithoutALeadingSlashAsAnAttemptedRestCall() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(false);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.findings())
                .extracting(Finding::message)
                .anyMatch(message -> message.contains("Эндпоинт POST reconciliation-note не найден"));
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
        verifyNoInteractions(cmdbAliasLookup);
    }

    @Test
    void emptyDiagramIsReportedAsMissingParticipantsNotAsAWrongDiagramType() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("empty_body.puml"));

        assertThat(result.valid()).isFalse();
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e.validation.participants.missing")
                .doesNotContain("e2e.validation.diagram.not_sequence", "e2e.validation.syntax.invalid");
        assertThat(result.recognizedParticipants()).isEmpty();
        assertThat(result.unrecognizedParticipants()).isEmpty();
        assertThat(result.recognizedCalls()).isEmpty();
        assertThat(result.unrecognizedCalls()).isEmpty();
        verifyNoInteractions(restEndpointLookup);
    }

    @Test
    void doesNotSilentlyPickTheSystemWhenTheMnemonicIsAlsoAContainerCode() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "dashboard", new ResolvedParticipant("dashboard", "[REMOVED!]Dashboard API&UI", Kind.SYSTEM,
                        "dashboard", new ResolvedParticipant("dashboard", "Dashboard", Kind.CONTAINER,
                                "fdmshowcaseapp"))));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(AMBIGUOUS_MNEMONIC_PUML);

        assertThat(result.recognizedParticipants()).isEmpty();
        assertThat(result.unrecognizedParticipants())
                .extracting(UnrecognizedParticipant::alias)
                .contains("dashboard");
        assertThat(result.recognizedCalls()).isEmpty();
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e.validation.participant.ambiguous");
        assertThat(result.findings())
                .extracting(Finding::message)
                .anyMatch(message -> message.contains("[REMOVED!]Dashboard API&UI") && message.contains("fdmshowcaseapp"));
        verifyNoInteractions(restEndpointLookup);
    }

    @Test
    void validationIsDeterministicForTheSameTextAndLookupState() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        String text = fixture("universal.puml");

        EngineResult first = engine.validate(text);
        EngineResult second = engine.validate(text);

        assertThat(first).isEqualTo(second);
    }

    private static Map<String, ResolvedParticipant> realCmdbData() {
        return Map.of(
                "b2c-digital-payments-bnpl", new ResolvedParticipant(
                        "b2c-digital-payments-bnpl", "b2c-digital-payments-bnpl", Kind.SYSTEM),
                "antispam", new ResolvedParticipant("antispam", "Антиспам", Kind.SYSTEM),
                "ai-tool", new ResolvedParticipant("ai-tool", "AI Tool", Kind.SYSTEM),
                "arfix", new ResolvedParticipant("arfix", "AR Collection", Kind.SYSTEM));
    }

    private static final String AMBIGUOUS_MNEMONIC_PUML = """
            @startuml
            participant BLN
            participant dashboard
            BLN -> dashboard: GET /api/v4/e2e
            @enduml
            """;

    private static final String DOTTED_MNEMONIC_PUML = """
            @startuml
            participant ext_Example.SomeDetail as Short
            participant Other as other
            Short -> other: GET /ping
            @enduml
            """;

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
