package ru.beeline.staging.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.beeline.staging.client.DocumentServiceClient;
import ru.beeline.staging.e2e.CmdbAliasLookup;
import ru.beeline.staging.e2e.E2ePlantUmlValidation;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.e2e.PlantUmlValidationEngine;
import ru.beeline.staging.e2e.RestEndpointLookup;
import ru.beeline.staging.exception.DocumentAccessDeniedException;
import ru.beeline.staging.exception.DocumentNotFoundException;
import ru.beeline.staging.exception.DocumentServiceUnavailableException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class E2eValidationControllerTest {

    private static final String VALID_PUML = fixture("universal.puml");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DocumentServiceClient documentServiceClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString(), anyString())).thenReturn(true);
        PlantUmlValidationEngine engine =
                new PlantUmlValidationEngine(new PlantUmlDiagramParser(), cmdbAliasLookup, restEndpointLookup);

        documentServiceClient = mock(DocumentServiceClient.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new E2eValidationController(new E2ePlantUmlValidation(engine), documentServiceClient))
                .build();
    }

    @Test
    void validatesTextInBody() throws Exception {
        Map<String, Object> request = Map.of("plantUml", VALID_PUML, "name", "Сценарий");

        mockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void rejectsEmptyPlantUmlBody() throws Exception {
        Map<String, Object> request = Map.of("plantUml", "  ");

        mockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bodyAndDocIdValidationProduceTheSameReportForTheSameText() throws Exception {
        when(documentServiceClient.fetchContent(42L)).thenReturn(VALID_PUML);
        Map<String, Object> request = Map.of("plantUml", VALID_PUML, "name", "Сценарий");

        String bodyResponse = mockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String docIdResponse = mockMvc.perform(post("/api/v1/e2e/validate/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Сценарий"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(docIdResponse).isEqualTo(bodyResponse);
    }

    @Test
    void validatesDocumentWithoutBodyAsIfMetadataWasEmpty() throws Exception {
        when(documentServiceClient.fetchContent(42L)).thenReturn(VALID_PUML);

        mockMvc.perform(post("/api/v1/e2e/validate/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notices[?(@.code == 'e2e_plantuml.validation.metadata.empty_name')]").exists());
    }

    @Test
    void appliesMetadataFromBodyWhenValidatingByDocId() throws Exception {
        when(documentServiceClient.fetchContent(42L)).thenReturn(VALID_PUML);

        mockMvc.perform(post("/api/v1/e2e/validate/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Сценарий\",\"biStepCode\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.notices[?(@.code == 'e2e_plantuml.validation.bi_step.invalid')].level")
                        .value("error"));
    }

    @Test
    void rejectsBlankBiStepCodeLikeTheLoaderDoes() throws Exception {
        mockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "plantUml", VALID_PUML, "name", "Сценарий", "biStepCode", " "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notices[?(@.code == 'e2e_plantuml.validation.bi_step.invalid')]").exists());
    }

    @Test
    void measuresTheSizeLimitInUtf8BytesLikeTheLoader() throws Exception {
        String cyrillic = "@startuml\n' " + "ж".repeat(300_000) + "\n@enduml";

        mockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("plantUml", cyrillic, "name", "Сценарий"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage")
                        .value("PlantUML text exceeds the maximum allowed size of 524288 bytes"));
    }

    @Test
    void measuresTheSizeLimitOfADocumentInUtf8Bytes() throws Exception {
        when(documentServiceClient.fetchContent(42L)).thenReturn("@startuml\n' " + "ж".repeat(300_000) + "\n@enduml");

        mockMvc.perform(post("/api/v1/e2e/validate/42"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundWhenDocumentIsMissing() throws Exception {
        when(documentServiceClient.fetchContent(anyLong())).thenThrow(new DocumentNotFoundException(99L));

        mockMvc.perform(post("/api/v1/e2e/validate/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsServiceUnavailableWhenDocumentServiceIsDown() throws Exception {
        when(documentServiceClient.fetchContent(anyLong()))
                .thenThrow(new DocumentServiceUnavailableException("boom", new RuntimeException("boom")));

        mockMvc.perform(post("/api/v1/e2e/validate/1"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void returnsForbiddenRatherThanUnavailableWhenDocumentIsNotPublic() throws Exception {
        when(documentServiceClient.fetchContent(anyLong())).thenThrow(new DocumentAccessDeniedException(7L));

        mockMvc.perform(post("/api/v1/e2e/validate/7"))
                .andExpect(status().isForbidden());
    }

    @Test
    void doesNotLeakInternalDetailsWhenTheEngineFailsUnexpectedly() throws Exception {
        CmdbAliasLookup failingCmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(failingCmdbAliasLookup.resolveAll(any())).thenThrow(new IllegalStateException(
                "Failed to fetch products by aliases: url=http://eafdmmart-func-fdm-products/api/v1/product/by-aliases?aliases=A0"));
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        PlantUmlValidationEngine failingEngine =
                new PlantUmlValidationEngine(new PlantUmlDiagramParser(), failingCmdbAliasLookup, restEndpointLookup);
        MockMvc failingMockMvc = MockMvcBuilders
                .standaloneSetup(new E2eValidationController(new E2ePlantUmlValidation(failingEngine), documentServiceClient)).build();
        Map<String, Object> request = Map.of("plantUml", VALID_PUML, "name", "Сценарий");

        failingMockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorMessage").value("Validation failed due to an internal error, try again later"));
    }

    private static String fixture(String name) {
        try (InputStream in = E2eValidationControllerTest.class.getResourceAsStream("/e2e/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
