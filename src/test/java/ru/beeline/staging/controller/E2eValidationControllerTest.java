package ru.beeline.staging.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.beeline.staging.client.DocumentServiceClient;
import ru.beeline.staging.dto.e2e.E2eValidateRequest;
import ru.beeline.staging.e2e.CmdbAliasLookup;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.e2e.PlantUmlValidationEngine;
import ru.beeline.staging.e2e.RestEndpointLookup;
import ru.beeline.staging.exception.DocumentNotFoundException;
import ru.beeline.staging.exception.DocumentServiceUnavailableException;

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

    private static final String VALID_PUML = """
            @startuml
            participant CRM as crm
            crm -> crm: GET /api/v1/order
            @enduml
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DocumentServiceClient documentServiceClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CmdbAliasLookup cmdbAliasLookup = mock(CmdbAliasLookup.class);
        when(cmdbAliasLookup.resolveAll(any())).thenReturn(Map.of());
        RestEndpointLookup restEndpointLookup = mock(RestEndpointLookup.class);
        when(restEndpointLookup.exists(anyString(), anyString())).thenReturn(true);
        PlantUmlValidationEngine engine =
                new PlantUmlValidationEngine(new PlantUmlDiagramParser(), cmdbAliasLookup, restEndpointLookup);

        documentServiceClient = mock(DocumentServiceClient.class);

        mockMvc = MockMvcBuilders.standaloneSetup(new E2eValidationController(engine, documentServiceClient)).build();
    }

    @Test
    void validatesTextInBody() throws Exception {
        E2eValidateRequest request = new E2eValidateRequest(VALID_PUML, null, null, null);

        mockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void rejectsEmptyPlantUmlBody() throws Exception {
        E2eValidateRequest request = new E2eValidateRequest("  ", null, null, null);

        mockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bodyAndDocIdValidationProduceTheSameReportForTheSameText() throws Exception {
        when(documentServiceClient.fetchContent(42L)).thenReturn(VALID_PUML);
        E2eValidateRequest request = new E2eValidateRequest(VALID_PUML, null, null, null);

        String bodyResponse = mockMvc.perform(post("/api/v1/e2e/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String docIdResponse = mockMvc.perform(post("/api/v1/e2e/validate/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(docIdResponse).isEqualTo(bodyResponse);
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
}
