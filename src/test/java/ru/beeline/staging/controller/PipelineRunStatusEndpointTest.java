package ru.beeline.staging.controller;

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
                new PipelineRunStatusSnapshot(RUN_ID, "e2e-plantuml", "E2E-001", "completed", "saver", 2)));

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
