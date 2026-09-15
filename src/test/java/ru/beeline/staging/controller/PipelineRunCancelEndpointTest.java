package ru.beeline.staging.controller;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.PipelineHitlService;
import ru.beeline.staging.service.PipelineRunImportService;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PipelineRunCancelEndpointTest {

    private static final long RUN_ID = 7788L;

    private PipelineRunRepository pipelineRunRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        PipelineRunHitlController controller = new PipelineRunHitlController(
                mock(PipelineRunImportService.class),
                new PipelineHitlService(pipelineRunRepository, new SimpleMeterRegistry()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PipelineRunImportExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/pipeline-runs/{runId}/cancel отвечает 202 и статусом cancelled")
    void cancelsRun() throws Exception {
        givenRun("awaiting_review");
        when(pipelineRunRepository.markCancelled(RUN_ID, "пользователь прервал импорт")).thenReturn(1);

        mockMvc.perform(post("/api/v1/pipeline-runs/{runId}/cancel", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"пользователь прервал импорт\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(RUN_ID))
                .andExpect(jsonPath("$.status").value("cancelled"));

        verify(pipelineRunRepository).markCancelled(RUN_ID, "пользователь прервал импорт");
    }

    @Test
    @DisplayName("Тело запроса необязательно")
    void acceptsEmptyBody() throws Exception {
        givenRun("pending");
        when(pipelineRunRepository.markCancelled(RUN_ID, "Отменено пользователем")).thenReturn(1);

        mockMvc.perform(post("/api/v1/pipeline-runs/{runId}/cancel", RUN_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    @DisplayName("Запуск не найден — 404 {error}")
    void returnsNotFound() throws Exception {
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/pipeline-runs/{runId}/cancel", RUN_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("Запуск уже завершён — 409 {error}")
    void returnsConflictForTerminalRun() throws Exception {
        givenRun("completed");
        when(pipelineRunRepository.markCancelled(RUN_ID, "Отменено пользователем")).thenReturn(0);

        mockMvc.perform(post("/api/v1/pipeline-runs/{runId}/cancel", RUN_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("runId не число — 400 {errorMessage}")
    void returnsBadRequestForMalformedRunId() throws Exception {
        mockMvc.perform(post("/api/v1/pipeline-runs/{runId}/cancel", "abc").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").exists());
    }

    private void givenRun(String status) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setArtifactType("usecase");
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
