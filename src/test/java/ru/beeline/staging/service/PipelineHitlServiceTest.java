package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.pipelinerun.ApplyPipelineRunResponse;
import ru.beeline.staging.dto.pipelinerun.CancelPipelineRunResponse;
import ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsRequest;
import ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsResponse;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.exception.PipelineRunUnresolvedPartsException;
import ru.beeline.staging.pipeline.manual.ManualOperations;
import ru.beeline.staging.repository.ImportDecisionRepository;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineHitlServiceTest {

    private static final long RUN_ID = 7788L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PipelineRunRepository pipelineRunRepository;
    private ImportDecisionRepository importDecisionRepository;
    private PipelineExecutionService pipelineExecutionService;
    private SimpleMeterRegistry meterRegistry;
    private ManualOperations manualOperations;
    private PipelineHitlService service;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        importDecisionRepository = mock(ImportDecisionRepository.class);
        pipelineExecutionService = mock(PipelineExecutionService.class);
        meterRegistry = new SimpleMeterRegistry();
        manualOperations = mock(ManualOperations.class);
        service = new PipelineHitlService(pipelineRunRepository, importDecisionRepository, pipelineExecutionService,
                meterRegistry, manualOperations);
    }

    @Test
    @DisplayName("Решение без target.stepVersionId — 400, ничего не пишется")
    void rejectsDecisionWithoutStepVersionId() {
        givenRun("awaiting_review");
        PipelineRunDecisionsRequest.Decision decision = mapExisting("P-02");
        decision.setTarget(objectMapper.createObjectNode().put("type", "GET"));

        assertThatThrownBy(() -> service.decide(RUN_ID, request(decision)))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("stepVersionId");
        verify(importDecisionRepository, never()).upsert(anyLong(), anyString(), anyString(), any(), any());
        verify(pipelineRunRepository, never()).markReviewing(anyLong());
    }

    @Test
    @DisplayName("Два решения по одной части в запросе — 400, ничего не пишется")
    void rejectsDuplicatePartInRequest() {
        givenRun("awaiting_review");
        PipelineRunDecisionsRequest request = new PipelineRunDecisionsRequest();
        request.setDecisions(List.of(mapExisting("P-02"), mapExisting(" P-02 ")));

        assertThatThrownBy(() -> service.decide(RUN_ID, request))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("P-02");
        verify(importDecisionRepository, never()).upsert(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Запуск в паузе переводится в cancelled, причина уходит в аудит")
    void cancelsRunWithReason() {
        givenRun("awaiting_review");
        when(pipelineRunRepository.markCancelled(RUN_ID, "пользователь прервал импорт")).thenReturn(1);

        CancelPipelineRunResponse response = service.cancel(RUN_ID, "  пользователь прервал импорт ");

        assertThat(response.runId()).isEqualTo(RUN_ID);
        assertThat(response.status()).isEqualTo("cancelled");
        verify(pipelineRunRepository).markCancelled(RUN_ID, "пользователь прервал импорт");
        assertThat(meterRegistry.counter("staging_pipeline_runs_total",
                "artifact_type", "usecase", "status", "cancelled").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Без причины в аудит пишется причина по умолчанию")
    void usesDefaultReason() {
        givenRun("transforming");
        when(pipelineRunRepository.markCancelled(RUN_ID, "Отменено пользователем")).thenReturn(1);

        service.cancel(RUN_ID, null);

        verify(pipelineRunRepository).markCancelled(RUN_ID, "Отменено пользователем");
    }

    @Test
    @DisplayName("Несуществующий запуск — 404, состояние не меняется")
    void rejectsUnknownRun() {
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(RUN_ID, null))
                .isInstanceOf(PipelineRunNotFoundException.class);
        verify(pipelineRunRepository, never()).markCancelled(anyLong(), anyString());
    }

    @Test
    @DisplayName("Терминальный или применяемый запуск — 409 с текущим статусом")
    void rejectsRunThatCannotBeCancelled() {
        givenRun("saving");
        when(pipelineRunRepository.markCancelled(RUN_ID, "Отменено пользователем")).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(RUN_ID, null))
                .isInstanceOf(PipelineRunConflictException.class)
                .hasMessageContaining("saving");
    }

    @Test
    @DisplayName("Неположительный runId — 400")
    void rejectsNonPositiveRunId() {
        assertThatThrownBy(() -> service.cancel(0L, null))
                .isInstanceOf(PipelineRunBadRequestException.class);
    }

    @Test
    @DisplayName("Решения записываются, запуск переходит в reviewing, считается остаток нерешённых частей")
    void acceptsDecisions() {
        givenRun("awaiting_review");
        when(pipelineRunRepository.markReviewing(RUN_ID)).thenReturn(1);
        when(importDecisionRepository.findByRunId(RUN_ID)).thenReturn(List.of(
                new ImportDecision(1L, RUN_ID, "P-02", "map_existing", "{}", null)));

        PipelineRunDecisionsResponse response = service.decide(RUN_ID, request(mapExisting("P-02")));

        verify(importDecisionRepository).upsert(eq(RUN_ID), eq("P-02"), eq("map_existing"),
                org.mockito.ArgumentMatchers.contains("stepVersionId"),
                org.mockito.ArgumentMatchers.contains("555"));
        assertThat(response.status()).isEqualTo("reviewing");
        assertThat(response.applied()).isEqualTo(1);
        assertThat(response.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("Решение применяется к канону сразу — фаза 2 в окне паузы")
    void appliesDecisionToTheCanonicalModel() {
        givenRun("awaiting_review");
        when(pipelineRunRepository.markReviewing(RUN_ID)).thenReturn(1);

        service.decide(RUN_ID, request(mapExisting("P-02")));

        ArgumentCaptor<ImportDecision> applied = ArgumentCaptor.forClass(ImportDecision.class);
        verify(manualOperations).applyDecision(eq("usecase"), eq(RUN_ID), applied.capture());
        assertThat(applied.getValue().partId()).isEqualTo("P-02");
        assertThat(applied.getValue().targetJson()).contains("stepVersionId");
        assertThat(applied.getValue().connectionOperationJson()).contains("555");
    }

    @Test
    @DisplayName("map_existing без connectionOperation.id — 400, ничего не пишется")
    void rejectsMapExistingWithoutConnection() {
        givenRun("awaiting_review");
        PipelineRunDecisionsRequest.Decision decision = new PipelineRunDecisionsRequest.Decision();
        decision.setPartId("P-03");
        decision.setType("map_existing");
        decision.setTarget(objectMapper.createObjectNode().put("stepVersionId", 2));

        assertThatThrownBy(() -> service.decide(RUN_ID, request(decision)))
                .isInstanceOf(PipelineRunBadRequestException.class);
        verify(importDecisionRepository, never()).upsert(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Решение по части не из контекста паузы — 404")
    void rejectsUnknownPart() {
        givenRun("reviewing");

        assertThatThrownBy(() -> service.decide(RUN_ID, request(mapExisting("P-99"))))
                .isInstanceOf(PipelineRunNotFoundException.class);
    }

    @Test
    @DisplayName("Решения вне паузы — 409")
    void rejectsDecisionsOutsideReview() {
        givenRun("completed");

        assertThatThrownBy(() -> service.decide(RUN_ID, request(mapExisting("P-02"))))
                .isInstanceOf(PipelineRunConflictException.class);
    }

    @Test
    @DisplayName("Apply при нерешённых частях — 409 со списком unresolvedParts, цепочка не продолжается")
    void rejectsApplyWithUnresolvedParts() {
        givenRun("reviewing");
        when(importDecisionRepository.findByRunId(RUN_ID)).thenReturn(List.of(
                new ImportDecision(1L, RUN_ID, "P-02", "map_existing", "{}", null)));

        assertThatThrownBy(() -> service.apply(RUN_ID, null))
                .isInstanceOfSatisfying(PipelineRunUnresolvedPartsException.class,
                        e -> assertThat(e.getUnresolvedParts()).containsExactly("P-03"));
        verify(pipelineRunRepository, never()).markApplying(anyLong());
        verify(pipelineExecutionService, never()).submitArtifactChain(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("Apply со всеми решениями — applying и продолжение цепочки стадией saver")
    void appliesRun() {
        givenRun("reviewing");
        when(importDecisionRepository.findByRunId(RUN_ID)).thenReturn(List.of(
                new ImportDecision(1L, RUN_ID, "P-02", "map_existing", "{}", null),
                new ImportDecision(2L, RUN_ID, "P-03", "planned", "{}", null)));
        when(pipelineRunRepository.markApplying(RUN_ID)).thenReturn(1);

        ApplyPipelineRunResponse response = service.apply(RUN_ID, "ок, применяю");

        assertThat(response.status()).isEqualTo("applying");
        assertThat(response.statusUrl()).isEqualTo("/api/v1/pipeline-runs/7788/status?waitFor=terminal");
        verify(pipelineExecutionService).submitArtifactChain(RUN_ID, "usecase", "usecase");
    }

    @Test
    @DisplayName("Apply уже завершённого запуска — 409")
    void rejectsApplyOfCompletedRun() {
        givenRun("completed");

        assertThatThrownBy(() -> service.apply(RUN_ID, null))
                .isInstanceOf(PipelineRunConflictException.class)
                .hasMessageContaining("completed");
    }

    @Test
    @DisplayName("planned с переданной архитектурной операцией — 400, ничего не пишется")
    void rejectsPlannedWithConnectionOperation() {
        givenRun("awaiting_review");
        PipelineRunDecisionsRequest.Decision decision = planned("P-02");
        decision.setConnectionOperation(objectMapper.createObjectNode().put("id", 555));

        assertThatThrownBy(() -> service.decide(RUN_ID, request(decision)))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("planned");
        verify(importDecisionRepository, never()).upsert(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("planned без операции — связь в решении остаётся пустой")
    void storesPlannedWithoutConnection() {
        givenRun("awaiting_review");
        when(pipelineRunRepository.markReviewing(RUN_ID)).thenReturn(1);
        PipelineRunDecisionsRequest.Decision decision = planned("P-02");
        decision.setConnectionOperation(objectMapper.createObjectNode());

        service.decide(RUN_ID, request(decision));

        verify(importDecisionRepository).upsert(eq(RUN_ID), eq("P-02"), eq("planned"),
                org.mockito.ArgumentMatchers.contains("stepVersionId"),
                org.mockito.ArgumentMatchers.isNull());
    }

    private PipelineRunDecisionsRequest.Decision planned(String partId) {
        PipelineRunDecisionsRequest.Decision decision = new PipelineRunDecisionsRequest.Decision();
        decision.setPartId(partId);
        decision.setType("planned");
        decision.setTarget(objectMapper.createObjectNode()
                .put("stepVersionId", 2)
                .put("type", "GET")
                .put("name", "/users"));
        return decision;
    }

    private PipelineRunDecisionsRequest request(PipelineRunDecisionsRequest.Decision decision) {
        PipelineRunDecisionsRequest request = new PipelineRunDecisionsRequest();
        request.setDecisions(List.of(decision));
        return request;
    }

    private PipelineRunDecisionsRequest.Decision mapExisting(String partId) {
        PipelineRunDecisionsRequest.Decision decision = new PipelineRunDecisionsRequest.Decision();
        decision.setPartId(partId);
        decision.setType("map_existing");
        decision.setTarget(objectMapper.createObjectNode()
                .put("stepVersionId", 2)
                .put("type", "GET")
                .put("name", "/users"));
        decision.setConnectionOperation(objectMapper.createObjectNode()
                .put("id", 555)
                .put("operationType", "GET")
                .put("operationName", "/users"));
        return decision;
    }

    private void givenRun(String status) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setArtifactType("usecase");
        run.setArtifactUid("UC-001");
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(manualOperations.unmappedParts("usecase", RUN_ID)).thenReturn(List.of("P-02", "P-03"));
    }
}
