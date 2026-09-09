package ru.beeline.staging.e2e;

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

/**
 * The CMDB/endpoint data used against "universal.puml" here is real dev-CMDB content, verified
 * end-to-end against the deployed dev server (see universal.puml's own header comment) — not
 * invented mnemonics like the earlier QA-supplied files turned out to be.
 */
class PlantUmlValidationEngineTest {

    private final PlantUmlDiagramParser parser = new PlantUmlDiagramParser();

    @Test
    void reportsValidWhenAllRealParticipantsAreRecognizedAndEveryLookupIsPermissive() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.recognizedParticipants()).hasSize(4);
        // "Client" is an external actor, deliberately not a CMDB entity
        assertThat(result.unrecognizedParticipants())
                .extracting(UnrecognizedParticipant::alias)
                .containsExactly("Client");
        // every message shaped like "METHOD token" gets recognized once the lookup is fully permissive
        // — including the deliberately-fake "POST reconciliation-note" at line 32, which is why this is
        // 7, not 6: see recognizesOnlyTheCallsThatReallyExistOnTheRealDevCmdbData for the precise picture
        assertThat(result.recognizedCalls()).hasSize(7);
        // the four narrative "200 OK"/result messages never match the REST-call regex at all
        assertThat(result.unrecognizedCalls()).hasSize(4);
    }

    @Test
    void resolvesParticipantByCmdbMnemonicInTheNamePositionNotOnlyByTheShortAlias() {
        // BNPL is declared as participant "b2c-digital-payments-bnpl" as BNPL — CMDB only recognizes
        // the full name; the short "as" alias "BNPL" itself is not a CMDB entity
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "b2c-digital-payments-bnpl", new ResolvedParticipant(
                        "b2c-digital-payments-bnpl", "b2c-digital-payments-bnpl", Kind.SYSTEM)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.recognizedParticipants())
                .extracting(RecognizedParticipant::alias)
                .contains("BNPL");
    }

    @Test
    void resolvesAMnemonicByThePrefixBeforeTheFirstDotWhenTheFullNameIsntRegistered() {
        // "<CMDB code>.<qualifier>" is a real CMDB naming convention (confirmed across every QA
        // эталон we were handed), not something tied to any one diagram — exercised with a small
        // inline diagram since this is pure resolution logic, not CMDB content
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "ext_Example", new ResolvedParticipant("ext_Example", "Example System", Kind.CONTAINER)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

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
        // exact end-to-end picture verified live against the dev server: all 6 real REST calls
        // recognized, including the path-template match (/api/v1/payment/{paymentId}/paymentItem);
        // the fabricated "POST reconciliation-note" correctly stays unrecognized here since its
        // owner (arfix) genuinely doesn't have that operation
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(eq("b2c-digital-payments-bnpl"), anyString(), eq("POST"), eq("/command/createApplication")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("antispam"), anyString(), eq("GET"), eq("/api/v1/calls/")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("antispam"), anyString(), eq("POST"), eq("/api/v1/calls/feedback")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("ai-tool"), anyString(), eq("POST"), eq("/chat/completions")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("b2c-digital-payments-bnpl"), anyString(), eq("POST"), eq("/command/completePayment")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("arfix"), anyString(), eq("GET"), eq("/api/v1/payment/12345/paymentItem")))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("arfix"), anyString(), eq("POST"), eq("reconciliation-note")))
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
    void flagsUnrecognizedParticipantsAsWarningNotBlockingValidity() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.unrecognizedParticipants()).hasSize(5);
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e.validation.participant.unrecognized");
    }

    @Test
    void flagsCallsWithoutMatchingRestEndpointAsUnrecognized() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

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
        // a downstream failure (fdm-products 500, network blip, ...) on one call must not 503 the
        // whole diagram — the rest is still worth validating
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(eq("arfix"), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));
        when(restEndpointLookup.exists(eq("antispam"), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("ai-tool"), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(restEndpointLookup.exists(eq("b2c-digital-payments-bnpl"), anyString(), anyString(), anyString()))
                .thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        assertThat(result.valid()).isTrue();
        // the two calls to ARFix (lines 29 and 32) hit the broken lookup, the rest didn't
        assertThat(result.findings())
                .filteredOn(f -> f.code().equals("e2e.validation.call.check_failed"))
                .hasSize(2);
    }

    @Test
    void doesNotCountAnEndpointThatExistsOnlyOnAnotherParticipant() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        // GET /api/v1/calls/ is stubbed as absent on antispam but present on ai-tool — the check must
        // still come back negative, since the diagram actually addresses antispam, not ai-tool
        when(restEndpointLookup.exists(eq("antispam"), anyString(), eq("GET"), eq("/api/v1/calls/")))
                .thenReturn(false);
        when(restEndpointLookup.exists(eq("ai-tool"), anyString(), eq("GET"), eq("/api/v1/calls/")))
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
        when(restEndpointLookup.exists(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("universal.puml"));

        // "POST reconciliation-note" (line 32) must be parsed as method=POST path=reconciliation-note,
        // not fall into the generic "no endpoint declared" bucket
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
        // An empty @startuml/@enduml block is the one real input that reaches this rule: no construct
        // that commits PlantUML to the Sequence type leaves the diagram without a participant (note
        // over X / activate A / create A all auto-create one). Before SFDM-4087's fix this input came
        // back as e2e.validation.diagram.not_sequence, telling the user the wrong reason.
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
        // there is no participant to resolve and no call to check
        verifyNoInteractions(restEndpointLookup);
    }

    @Test
    void validationIsDeterministicForTheSameTextAndLookupState() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(realCmdbData());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        String text = fixture("universal.puml");

        EngineResult first = engine.validate(text);
        EngineResult second = engine.validate(text);

        assertThat(first).isEqualTo(second);
    }

    /** The four real systems in universal.puml, exactly as resolved by the live dev CMDB. */
    private static Map<String, ResolvedParticipant> realCmdbData() {
        return Map.of(
                "b2c-digital-payments-bnpl", new ResolvedParticipant(
                        "b2c-digital-payments-bnpl", "b2c-digital-payments-bnpl", Kind.SYSTEM),
                "antispam", new ResolvedParticipant("antispam", "Антиспам", Kind.SYSTEM),
                "ai-tool", new ResolvedParticipant("ai-tool", "AI Tool", Kind.SYSTEM),
                "arfix", new ResolvedParticipant("arfix", "AR Collection", Kind.SYSTEM));
    }

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
