package ru.beeline.staging.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.PipelineStageLog;
import ru.beeline.staging.exception.PipelineRunCancelledException;
import ru.beeline.staging.repository.ArtifactBatchRepository;
import ru.beeline.staging.repository.PipelineDefinitionEntryRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.PipelineStageLogRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunServiceCancellationTest {

    private static final long RUN_ID = 7788L;

    private PipelineRunRepository      runRepository;
    private PipelineStageLogRepository stageLogRepository;
    private PipelineRunService         service;

    @BeforeEach
    void setUp() {
        runRepository = mock(PipelineRunRepository.class);
        stageLogRepository = mock(PipelineStageLogRepository.class);
        when(stageLogRepository.save(any(PipelineStageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service = new PipelineRunService(runRepository, stageLogRepository,
                mock(ArtifactBatchRepository.class), mock(PipelineDefinitionEntryRepository.class),
                mock(ArtifactNoticeService.class), new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("Отменённый запуск не переходит в следующую стадию — saver не стартует")
    void doesNotStartStageOfCancelledRun() {
        givenRun("cancelled");
        when(runRepository.advanceStage(RUN_ID, "saving")).thenReturn(0);

        assertThatThrownBy(() -> service.startStage(RUN_ID, "saver", "rawDataRefId=1"))
                .isInstanceOf(PipelineRunCancelledException.class);
        verify(stageLogRepository, never()).save(any(PipelineStageLog.class));
    }

    @Test
    @DisplayName("Активный запуск переходит в статус стадии условным обновлением")
    void advancesActiveRun() {
        givenRun("transforming");
        when(runRepository.advanceStage(RUN_ID, "saving")).thenReturn(1);

        service.startStage(RUN_ID, "saver", "rawDataRefId=1");

        verify(runRepository).advanceStage(RUN_ID, "saving");
        verify(runRepository, never()).save(any(PipelineRun.class));
        verify(stageLogRepository).save(any(PipelineStageLog.class));
    }

    @Test
    @DisplayName("rawDataRefId пишется точечно — сущность со старым статусом не затирает cancelled")
    void updatesRawDataRefIdWithoutSavingEntity() {
        service.setRawDataRefId(RUN_ID, 42L);

        verify(runRepository).updateRawDataRefId(RUN_ID, 42L);
        verify(runRepository, never()).save(any(PipelineRun.class));
    }

    @Test
    @DisplayName("Отменённый запуск сторож не переводит в failed")
    void stallWatchdogIgnoresCancelledRun() {
        givenRun("cancelled");

        service.failStalledRun(RUN_ID, 12, 3);

        verify(runRepository, never()).markStalled(anyLong(), anyString(), anyString(), anyInt());
    }

    private void givenRun(String status) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setArtifactUid("UC-001");
        run.setArtifactType("usecase");
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
