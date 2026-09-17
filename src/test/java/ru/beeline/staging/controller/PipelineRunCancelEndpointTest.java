package ru.beeline.staging.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.usecase.UseCaseDraft;
import ru.beeline.staging.repository.ImportDecisionRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.service.PipelineExecutionService;
import ru.beeline.staging.service.PipelineHitlService;
import ru.beeline.staging.service.PipelineRunImportService;
import ru.beeline.staging.service.RunBranchResolver;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PipelineRunCancelEndpointTest {

    private static final long RUN_ID = 7788L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PipelineRunRepository pipelineRunRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        PipelineRunHitlController controller = new PipelineRunHitlController(
                mock(PipelineRunImportService.class),
                new PipelineHitlService(pipelineRunRepository, mock(ImportDecisionRepository.class),
                        mock(PipelineExecutionService.class), new SimpleMeterRegistry(), objectMapper,
                        mock(UseCaseLandscapeRepository.class), mock(RunBranchResolver.class)));
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

    @Test
    @DisplayName("POST /decisions отвечает 202 со статусом reviewing и остатком")
    void acceptsDecisions() throws Exception {
        givenRun("awaiting_review");
        when(pipelineRunRepository.markReviewing(RUN_ID)).thenReturn(1);

        mockMvc.perform(post("/api/v1/pipeline-runs/{runId}/decisions", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decisions": [{"partId": "P-02", "type": "create_new",
                                  "newRequest": {"productCode": "BC-9", "containerName": "Pay", "interfaceName": "pay_api"}}]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("reviewing"))
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.remaining").value(1));
    }

    @Test
    @DisplayName("POST /apply при нерешённой части — 409 {error, unresolvedParts}")
    void rejectsApplyWithUnresolvedParts() throws Exception {
        givenRun("awaiting_review");

        mockMvc.perform(post("/api/v1/pipeline-runs/{runId}/apply", RUN_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.unresolvedParts[0]").value("P-02"));
    }

    private void givenRun(String status) throws Exception {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setArtifactType("usecase");
        run.setDraftJson(objectMapper.writeValueAsString(new UseCaseDraft(
                new UseCaseDraft.Header("UC-001", "Заказ", null, "PRJ-1"), "main", List.of(),
                List.of(new UseCaseDraft.UnmappedPart("P-02", "interaction", 1, "main", "action", "GET /users",
                        "callee", List.of("gw"), "не найдено", "map_existing | create_new")))));
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
