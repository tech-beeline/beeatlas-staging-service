package ru.beeline.staging.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.pipelinerun.DeclinePipelineRunResponse;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.pipeline.manual.ManualOperations;
import ru.beeline.staging.repository.ImportDecisionRepository;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunDeclineTest {

    private static final long RUN_ID = 77L;

    private PipelineRunRepository pipelineRunRepository;
    private PipelineExecutionService pipelineExecutionService;
    private PipelineHitlService service;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        pipelineExecutionService = mock(PipelineExecutionService.class);
        service = new PipelineHitlService(pipelineRunRepository, mock(ImportDecisionRepository.class),
                pipelineExecutionService, new SimpleMeterRegistry(), mock(ManualOperations.class));
    }

    @Test
    @DisplayName("Отказ завершает прогон без публикации и не продолжает цепочку")
    void declineFinishesTheRunWithoutPublishing() {
        givenRun("awaiting_review");
        when(pipelineRunRepository.markDeclined(RUN_ID)).thenReturn(1);

        DeclinePipelineRunResponse response = service.decline(RUN_ID);

        assertThat(response.runId()).isEqualTo(RUN_ID);
        assertThat(response.status()).isEqualTo("completed_without_publish");
        verify(pipelineExecutionService, never()).submitArtifactChain(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Отказ возможен и после начала ревью")
    void declineWorksFromReviewing() {
        givenRun("reviewing");
        when(pipelineRunRepository.markDeclined(RUN_ID)).thenReturn(1);

        assertThat(service.decline(RUN_ID).status()).isEqualTo("completed_without_publish");
    }

    @Test
    @DisplayName("Отказаться от публикации вне паузы нельзя")
    void declineIsRejectedOutsideTheReviewStatuses() {
        givenRun("saving");

        assertThatThrownBy(() -> service.decline(RUN_ID))
                .isInstanceOf(PipelineRunConflictException.class)
                .hasMessageContaining("отказаться от публикации");
        verify(pipelineRunRepository, never()).markDeclined(RUN_ID);
    }

    @Test
    @DisplayName("Гонка за статус даёт конфликт, а не молчаливый успех")
    void declineLosesTheRaceWithAnotherDecision() {
        givenRun("awaiting_review");
        when(pipelineRunRepository.markDeclined(RUN_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.decline(RUN_ID)).isInstanceOf(PipelineRunConflictException.class);
    }

    @Test
    @DisplayName("Несуществующий запуск — 404")
    void declineOfAnUnknownRunIsNotFound() {
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decline(RUN_ID)).isInstanceOf(PipelineRunNotFoundException.class);
    }

    private void givenRun(String status) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setArtifactType("e2e-plantuml");
        run.setStatus(status);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
