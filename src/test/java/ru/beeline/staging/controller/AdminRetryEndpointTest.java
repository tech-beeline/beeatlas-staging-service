package ru.beeline.staging.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.ArtifactNoticeService;
import ru.beeline.staging.service.PipelineExecutionService;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.worker.PipelineTickScheduler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminRetryEndpointTest {

    private static final long RUN_ID = 999999999L;

    private PipelineRunRepository pipelineRunRepository;
    private PipelineRunService pipelineRunService;
    private PipelineExecutionService pipelineExecutionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        pipelineRunService = mock(PipelineRunService.class);
        pipelineExecutionService = mock(PipelineExecutionService.class);
        AdminController controller = new AdminController(
                mock(ConfigurationRepository.class),
                mock(PipelineTickScheduler.class),
                pipelineExecutionService,
                pipelineRunService,
                pipelineRunRepository,
                mock(ArtifactNoticeService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Retry несуществующего прогона — 404, как у /details")
    void returnsNotFoundForUnknownRun() throws Exception {
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/pipeline-runs/{runId}/retry", RUN_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Pipeline run not found"))
                .andExpect(jsonPath("$.runId").value(RUN_ID));

        verify(pipelineRunService, never()).retryFailedRun(anyLong());
    }

    @Test
    @DisplayName("Retry прогона не в статусе failed — 409 с текущим статусом")
    void returnsConflictForRunThatIsNotFailed() throws Exception {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus("completed");
        run.setArtifactUid("UC-001");
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        mockMvc.perform(post("/admin/pipeline-runs/{runId}/retry", RUN_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.runId").value(RUN_ID));

        verify(pipelineRunService, never()).retryFailedRun(anyLong());
        verify(pipelineExecutionService, never()).submitArtifactChain(anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Retry упавшего прогона артефакта — 202 и перезапуск цепочки")
    void retriesFailedArtifactRun() throws Exception {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus("failed");
        run.setArtifactUid("UC-001");
        run.setArtifactType("usecase");
        run.setConfigurationId(5L);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        mockMvc.perform(post("/admin/pipeline-runs/{runId}/retry", RUN_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.retried").value(true));

        verify(pipelineRunService).retryFailedRun(RUN_ID);
        verify(pipelineExecutionService).submitArtifactChain(RUN_ID, "usecase", null);
    }
}
