package ru.beeline.staging.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the parser (and, where noted, the full engine) against the real QA reference diagrams —
 * the same files the e2e-import vision's эталоны are drawn from — rather than synthetic fixtures.
 * These pin down the concrete before/after numbers from the QA report: e.g. 03_DSIM had 29 calls
 * collapsing onto 12 distinct lines before the line-locator fix.
 */
class RealE2eReferenceDiagramsTest {

    private final PlantUmlDiagramParser parser = new PlantUmlDiagramParser();

    @Test
    void dsimFlashingParsesWithTwentyNineMessagesEachOnItsOwnLine() {
        ParseOutcome outcome = parser.parse(fixture("03_DSIM.Flashing_Min_Changes.puml"));

        assertThat(outcome.isParsed()).isTrue();
        assertThat(outcome.diagram().participants()).hasSize(8);
        assertThat(outcome.diagram().messages()).hasSize(29);
        assertDistinctLines(outcome.diagram().messages());
    }

    @Test
    void uc31AsyncOtaResponseParsesWithTwentyThreeMessagesEachOnItsOwnLine() {
        ParseOutcome outcome = parser.parse(fixture("04_UC3.1_Obrabotka_asinhronnogo_otveta_ot_OTA.puml"));

        assertThat(outcome.isParsed()).isTrue();
        assertThat(outcome.diagram().participants()).hasSize(5);
        assertThat(outcome.diagram().messages()).hasSize(23);
        assertDistinctLines(outcome.diagram().messages());
    }

    @Test
    void uc4DeliveryReportParsesWithSevenMessagesEachOnItsOwnLine() {
        ParseOutcome outcome = parser.parse(fixture("05_UC4._Poluchenie_otcheta_o_dostavke_SMS_na_postoyannyy_nomer.puml"));

        assertThat(outcome.isParsed()).isTrue();
        assertThat(outcome.diagram().participants()).hasSize(4);
        assertThat(outcome.diagram().messages()).hasSize(7);
        assertDistinctLines(outcome.diagram().messages());
    }

    @Test
    void uc5RepeatSmsParsesWithTwoMessagesEachOnItsOwnLine() {
        ParseOutcome outcome = parser.parse(fixture("06_UC5._Otpravka_povtornyh_SMS_na_postoyannyy_nomer.puml"));

        assertThat(outcome.isParsed()).isTrue();
        assertThat(outcome.diagram().participants()).hasSize(2);
        assertThat(outcome.diagram().messages()).hasSize(2);
        assertDistinctLines(outcome.diagram().messages());
    }

    /**
     * DFD_* files are level-1/level-2 data-flow diagrams built from {@code rectangle}/{@code database}
     * shapes, not PlantUML Sequence Diagrams — STG-04 scopes the validator to Sequence Diagrams only,
     * so these must be rejected rather than silently misread as an (empty) sequence diagram.
     */
    @Test
    void dfd1IsRejectedAsNotASequenceDiagram() {
        assertRejectedAsNotSequence("DFD_1_MNP_Interregional.puml");
    }

    @Test
    void dfd2IsRejectedAsNotASequenceDiagram() {
        assertRejectedAsNotSequence("DFD_2_P2_BDPN.puml");
    }

    @Test
    void dfdContextDiagramIsRejectedAsNotASequenceDiagram() {
        assertRejectedAsNotSequence("DFD_MNP_Interregional.puml");
    }

    /**
     * With CMDB resolving every participant and every REST endpoint existing, what's left over is
     * purely the regex's own call/no-call split — 4 of the 29 messages in 03_DSIM read as an attempted
     * REST call ("METHOD path"; three of the four have no leading slash, e.g. "POST
     * triggerImsiReplacement" — recognized only after the regex was widened past "METHOD /path").
     * The other 25 are narrative/lifecycle text and are expected to end up unrecognized.
     */
    @Test
    void dsimFlashingHasFourMessagesThatReadAsAttemptedRestCalls() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenAnswer(invocation -> {
            Set<String> keys = invocation.getArgument(0);
            java.util.Map<String, CmdbAliasLookup.ResolvedParticipant> resolved = new java.util.HashMap<>();
            for (String key : keys) {
                resolved.put(key, new CmdbAliasLookup.ResolvedParticipant(
                        key, key, CmdbAliasLookup.ResolvedParticipant.Kind.SYSTEM));
            }
            return resolved;
        });
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        PlantUmlValidationEngine engine = new PlantUmlValidationEngine(parser, cmdbAliasLookup, restEndpointLookup);
        EngineResult result = engine.validate(fixture("03_DSIM.Flashing_Min_Changes.puml"));

        assertThat(result.recognizedParticipants()).hasSize(8);
        assertThat(result.unrecognizedParticipants()).isEmpty();
        assertThat(result.recognizedCalls()).hasSize(4);
        assertThat(result.recognizedCalls())
                .extracting(call -> call.httpMethod() + " " + call.path())
                .containsExactlyInAnyOrder(
                        "GET /getServiceList",
                        "POST triggerImsiReplacement",
                        "POST ChangeSubscription",
                        "POST BreakSubscriptionWaiting");
        assertThat(result.unrecognizedCalls()).hasSize(25);
    }

    private void assertRejectedAsNotSequence(String fixtureName) {
        ParseOutcome outcome = parser.parse(fixture(fixtureName));

        assertThat(outcome.isParsed()).isFalse();
        assertThat(outcome.findings())
                .extracting(Finding::code)
                .containsExactly("e2e.validation.diagram.not_sequence");
    }

    private static void assertDistinctLines(List<ParsedDiagram.Message> messages) {
        List<Integer> lines = new ArrayList<>();
        for (ParsedDiagram.Message message : messages) {
            lines.add(message.line());
        }
        assertThat(new HashSet<>(lines)).hasSameSizeAs(lines);
    }

    private static String fixture(String name) {
        try (InputStream in = RealE2eReferenceDiagramsTest.class.getResourceAsStream("/e2e/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
