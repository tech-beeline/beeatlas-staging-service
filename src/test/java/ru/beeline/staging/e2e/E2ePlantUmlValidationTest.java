package ru.beeline.staging.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant.Kind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class E2ePlantUmlValidationTest {

    private static final String KNOWN_PARTICIPANTS = """
            @startuml
            participant "billing" as B
            participant "antispam" as A
            B -> A: GET /api/v1/check
            @enduml
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CmdbAliasLookup cmdbAliasLookup;
    private RestEndpointLookup restEndpointLookup;
    private E2ePlantUmlValidation validation;

    @BeforeEach
    void setUp() {
        cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "billing", new ResolvedParticipant("billing", "billing", Kind.SYSTEM),
                "antispam", new ResolvedParticipant("antispam", "antispam", Kind.SYSTEM)));
        restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);
        validation = new E2ePlantUmlValidation(
                new PlantUmlValidationEngine(new PlantUmlDiagramParser(), cmdbAliasLookup, restEndpointLookup));
    }

    @Test
    void acceptsARecognizedDiagramWithFullMetadata() {
        EngineResult result = validation.validate(KNOWN_PARTICIPANTS, metadata("{\"name\":\"Сценарий\",\"biStepCode\":\"BI-07\"}"));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void reportsUnrecognizedParticipantAsBlockingErrorInPipelineNotation() {
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of());

        EngineResult result = validation.validate(KNOWN_PARTICIPANTS, metadata("{\"name\":\"Сценарий\"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.findings())
                .extracting(Finding::code, Finding::level)
                .contains(tuple("e2e_plantuml.validation.participants.unrecognized", Finding.Level.ERROR));
    }

    @Test
    void reportsMissingEndpointAsCallsUnrecognizedWarning() {
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(false);

        EngineResult result = validation.validate(KNOWN_PARTICIPANTS, metadata("{\"name\":\"Сценарий\"}"));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings())
                .extracting(Finding::code, Finding::level)
                .containsExactly(tuple("e2e_plantuml.validation.calls.unrecognized", Finding.Level.WARNING));
    }

    @Test
    void reportsFailedEndpointCheckAsCallsUnrecognizedWarning() {
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("boom"));

        EngineResult result = validation.validate(KNOWN_PARTICIPANTS, metadata("{\"name\":\"Сценарий\"}"));

        assertThat(result.findings())
                .extracting(Finding::code, Finding::level)
                .containsExactly(tuple("e2e_plantuml.validation.calls.unrecognized", Finding.Level.WARNING));
    }

    @Test
    void reportsDiagramWithoutMessagesAsWarning() {
        String noMessages = """
                @startuml
                participant "billing" as B
                @enduml
                """;

        EngineResult result = validation.validate(noMessages, metadata("{\"name\":\"Сценарий\"}"));

        assertThat(result.findings())
                .extracting(Finding::code, Finding::level)
                .containsExactly(tuple("e2e_plantuml.validation.messages.empty", Finding.Level.WARNING));
    }

    @Test
    void reportsSyntaxErrorInPipelineNotation() {
        EngineResult result = validation.validate(fixture("syntax_error.puml"), metadata("{\"name\":\"Сценарий\"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.findings())
                .extracting(Finding::code)
                .contains("e2e_plantuml.validation.syntax.invalid")
                .allMatch(code -> code.startsWith("e2e_plantuml.validation."));
    }

    @Test
    void rejectsBlankBiStepCode() {
        EngineResult result = validation.validate(KNOWN_PARTICIPANTS, metadata("{\"name\":\"Сценарий\",\"biStepCode\":\"  \"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.findings())
                .extracting(Finding::code, Finding::level)
                .containsExactly(tuple("e2e_plantuml.validation.bi_step.invalid", Finding.Level.ERROR));
    }

    @Test
    void rejectsExplicitNullBiStepCode() {
        EngineResult result = validation.validate(KNOWN_PARTICIPANTS, metadata("{\"name\":\"Сценарий\",\"biStepCode\":null}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.findings())
                .extracting(Finding::code)
                .containsExactly("e2e_plantuml.validation.bi_step.invalid");
    }

    @Test
    void warnsAboutMissingNameWithoutBlocking() {
        EngineResult result = validation.validate(KNOWN_PARTICIPANTS, metadata("{}"));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings())
                .extracting(Finding::code, Finding::level)
                .containsExactly(tuple("e2e_plantuml.validation.metadata.empty_name", Finding.Level.WARNING));
    }

    @Test
    void treatsMissingMetadataAsEmptyObject() {
        EngineResult result = validation.validate(KNOWN_PARTICIPANTS, MissingNode.getInstance());

        assertThat(result.valid()).isTrue();
        assertThat(result.findings())
                .extracting(Finding::code)
                .containsExactly("e2e_plantuml.validation.metadata.empty_name");
    }

    private JsonNode metadata(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fixture(String name) {
        try (InputStream in = E2ePlantUmlValidationTest.class.getResourceAsStream("/e2e/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
