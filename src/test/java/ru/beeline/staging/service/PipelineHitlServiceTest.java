package ru.beeline.staging.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.pipelinerun.CancelPipelineRunResponse;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineHitlServiceTest {

    private static final long RUN_ID = 7788L;

    private PipelineRunRepository pipelineRunRepository;
    private SimpleMeterRegistry meterRegistry;
    private PipelineHitlService service;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new PipelineHitlService(pipelineRunRepository, meterRegistry);
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

    private void givenRun(String status) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setArtifactType("usecase");
        run.setArtifactUid("UC-001");
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
