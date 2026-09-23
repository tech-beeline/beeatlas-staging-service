package ru.beeline.staging.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.beeline.staging.dto.pipelinerun.PipelineRunStatusSnapshot;
import ru.beeline.staging.repository.ChildPipelineRunRepository;
import ru.beeline.staging.repository.PipelineRunDetailsRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.PipelineRunStatusRepository;
import ru.beeline.staging.repository.ScanRunRepository;
import ru.beeline.staging.service.PipelineRunStatusService;
import ru.beeline.staging.service.PipelineRunTextSearchService;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PipelineRunStatusEndpointTest {

    private static final long RUN_ID = 1644367L;

    private PipelineRunStatusRepository statusRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        statusRepository = mock(PipelineRunStatusRepository.class);
        PipelineRunsController controller = new PipelineRunsController(
                mock(ScanRunRepository.class),
                mock(PipelineRunDetailsRepository.class),
                mock(PipelineRunRepository.class),
                mock(ChildPipelineRunRepository.class),
                mock(PipelineRunTextSearchService.class),
                new PipelineRunStatusService(statusRepository, 300, 60000, 20, 1));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/v1/pipeline-runs/{runId}/status отдаёт статус запуска")
    void returnsStatus() throws Exception {
        when(statusRepository.findSnapshot(RUN_ID)).thenReturn(Optional.of(
                new PipelineRunStatusSnapshot(RUN_ID, "e2e-plantuml", "E2E-001", "completed", "saver", 2, null)));

        MvcResult started = mockMvc.perform(get("/api/v1/pipeline-runs/{runId}/status", RUN_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(RUN_ID))
                .andExpect(jsonPath("$.artifactType").value("e2e-plantuml"))
                .andExpect(jsonPath("$.artifactUid").value("E2E-001"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.stage").value("saver"))
                .andExpect(jsonPath("$.noticesCount").value(2))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.more").value(false));
    }

    @Test
    @DisplayName("В паузе awaiting_review блок result собирается из канонической модели")
    void returnsResultBlock() throws Exception {
        ObjectNode draft = JsonNodeFactory.instance.objectNode();
        draft.putObject("usecase").put("code", "UC-001");
        draft.putArray("mapped").addObject().put("partId", "P-01").put("status", "confirmed");
        draft.putArray("unmapped").addObject().put("partId", "P-04").put("suggestion", "map_existing | create_new");
        when(statusRepository.findSnapshot(RUN_ID)).thenReturn(Optional.of(
                new PipelineRunStatusSnapshot(RUN_ID, "usecase", "UC-001", "awaiting_review", "manual", 0, draft)));

        MvcResult started = mockMvc.perform(get("/api/v1/pipeline-runs/{runId}/status", RUN_ID)
                        .accept(MediaType.APPLICATION_JSON)
                        .param("waitFor", "awaiting_review"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("awaiting_review"))
                .andExpect(jsonPath("$.stage").value("manual"))
                .andExpect(jsonPath("$.result.usecase.code").value("UC-001"))
                .andExpect(jsonPath("$.result.mapped[0].status").value("confirmed"))
                .andExpect(jsonPath("$.result.unmapped[0].partId").value("P-04"))
                .andExpect(jsonPath("$.more").value(false));
    }

    @Test
    @DisplayName("Неизвестный runId — 404")
    void returnsNotFound() throws Exception {
        when(statusRepository.findSnapshot(RUN_ID)).thenReturn(Optional.empty());

        MvcResult started = mockMvc.perform(get("/api/v1/pipeline-runs/{runId}/status", RUN_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.runId").value(RUN_ID));
    }

    @Test
    @DisplayName("Недопустимый waitFor — 400")
    void rejectsUnknownWaitFor() throws Exception {
        MvcResult started = mockMvc.perform(get("/api/v1/pipeline-runs/{runId}/status", RUN_ID)
                        .accept(MediaType.APPLICATION_JSON)
                        .param("waitFor", "whenever"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
