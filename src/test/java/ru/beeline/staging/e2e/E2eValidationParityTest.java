package ru.beeline.staging.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.beeline.staging.client.DocumentServiceClient;
import ru.beeline.staging.controller.E2eValidationController;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant.Kind;
import ru.beeline.staging.pipeline.StageContext;
import ru.beeline.staging.pipeline.validator.PlantUmlE2EValidator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class E2eValidationParityTest {

    private static final String RECOGNIZED = """
            @startuml
            participant "billing" as B
            participant "antispam" as A
            B -> A: GET /api/v1/check
            B -> A: запрос без REST
            @enduml
            """;

    private static final List<String> METADATA = List.of(
            "{}",
            "{\"name\":\"Сценарий\"}",
            "{\"name\":\"  \"}",
            "{\"name\":\"Сценарий\",\"biStepCode\":\"BI-07\"}",
            "{\"name\":\"Сценарий\",\"biStepCode\":\"\"}",
            "{\"name\":\"Сценарий\",\"biStepCode\":null}");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private PlantUmlE2EValidator pipelineValidator;

    @BeforeEach
    void setUp() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of(
                "billing", new ResolvedParticipant("billing", "billing", Kind.SYSTEM),
                "antispam", new ResolvedParticipant("antispam", "antispam", Kind.SYSTEM)));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);
        E2ePlantUmlValidation validation = new E2ePlantUmlValidation(
                new PlantUmlValidationEngine(new PlantUmlDiagramParser(), cmdbAliasLookup, restEndpointLookup));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new E2eValidationController(validation, mock(DocumentServiceClient.class)))
                .build();
        pipelineValidator = new PlantUmlE2EValidator(validation, objectMapper);
    }

    static Stream<Arguments> cases() {
        List<String> texts = List.of(RECOGNIZED, fixture("universal.puml"), fixture("syntax_error.puml"),
                fixture("not_sequence.puml"), fixture("messages_empty.puml"), fixture("empty_body.puml"));
        List<Arguments> cases = new ArrayList<>();
        for (String text : texts) {
            for (String metadata : METADATA) {
                cases.add(Arguments.of(text, metadata));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void methodReportsExactlyWhatThePipelineValidatorStageReports(String plantUml, String metadataJson) throws Exception {
        ObjectNode payload = (ObjectNode) objectMapper.readTree(metadataJson);
        payload.put("plantUml", plantUml);

        String body = mockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode report = objectMapper.readTree(body);

        List<ArtifactNotice> pipelineNotices = pipelineValidator
                .validate("uid-1", plantUml, new StageContext(1L, "e2e-plantuml", "manual", null, payload))
                .notices();

        List<String> methodCodes = new ArrayList<>();
        report.get("notices").forEach(n -> methodCodes.add(n.get("code").asText() + ":" + n.get("level").asText()));
        List<String> pipelineCodes = pipelineNotices.stream().map(n -> n.code() + ":" + n.level()).toList();

        assertThat(methodCodes).containsExactlyInAnyOrderElementsOf(pipelineCodes);
        assertThat(report.get("valid").asBoolean())
                .isEqualTo(pipelineNotices.stream().noneMatch(n -> "error".equals(n.level())));
    }

    private static String fixture(String name) {
        try (InputStream in = E2eValidationParityTest.class.getResourceAsStream("/e2e/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
