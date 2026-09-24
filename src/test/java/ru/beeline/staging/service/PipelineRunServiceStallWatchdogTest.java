package ru.beeline.staging.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.repository.ArtifactBatchRepository;
import ru.beeline.staging.repository.PipelineDefinitionEntryRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.PipelineStageLogRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunServiceStallWatchdogTest {

    private static final long RUN_ID   = 1571951L;
    private static final int  ATTEMPTS = 12;
    private static final int  RETRY_CEILING = 3;

    private PipelineRunRepository       runRepository;
    private PipelineStageLogRepository  stageLogRepository;
    private PipelineRunService          service;

    @BeforeEach
    void setUp() {
        runRepository = mock(PipelineRunRepository.class);
        stageLogRepository = mock(PipelineStageLogRepository.class);

        when(stageLogRepository.findRunningStageNames(anyLong())).thenReturn(List.of("pre-adapter"));
        when(stageLogRepository.abandonRunningStages(anyLong(), anyString())).thenReturn(3);
        when(runRepository.markStalled(anyLong(), anyString(), anyString(), anyInt())).thenReturn(1);

        service = new PipelineRunService(runRepository, stageLogRepository,
                mock(ArtifactBatchRepository.class), mock(PipelineDefinitionEntryRepository.class),
                mock(ArtifactNoticeService.class), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "maxAutoRetries", RETRY_CEILING);
    }

    @Test
    @DisplayName("Зависший скан переводится в terminal и освобождает слот конфигурации")
    void forcesStalledScanTerminal() {
        givenRun("pending", null);

        service.failStalledRun(RUN_ID, ATTEMPTS, RETRY_CEILING);

        verify(runRepository).markStalled(eq(RUN_ID), contains("Stalled"), eq("pre-adapter"), eq(RETRY_CEILING));
    }

    @Test
    @DisplayName("retry_count поднимается до потолка — авторетрай не возвращает прогон в тот же цикл")
    void pinsRetryCountAtCeilingSoAutoRetryLeavesItAlone() {
        givenRun("pending", null);

        service.failStalledRun(RUN_ID, ATTEMPTS, RETRY_CEILING);

        verify(runRepository).markStalled(anyLong(), anyString(), anyString(), eq(RETRY_CEILING));
    }

    @Test
    @DisplayName("Брошенные стадии закрываются — прогон не остаётся вечно 'running' в UI")
    void closesDanglingRunningStages() {
        givenRun("pending", null);

        service.failStalledRun(RUN_ID, ATTEMPTS, RETRY_CEILING);

        verify(stageLogRepository).abandonRunningStages(eq(RUN_ID), contains("Stalled"));
    }

    @Test
    @DisplayName("Уже завершённый прогон сторож не трогает")
    void ignoresAlreadyTerminalRun() {
        givenRun("completed", null);

        service.failStalledRun(RUN_ID, ATTEMPTS, RETRY_CEILING);

        verify(runRepository, never()).markStalled(anyLong(), anyString(), anyString(), anyInt());
        verify(stageLogRepository, never()).abandonRunningStages(anyLong(), anyString());
    }

    @Test
    @DisplayName("Упавший прогон сторож не трогает — им занимается авторетрай")
    void ignoresFailedRun() {
        givenRun("failed", null);

        service.failStalledRun(RUN_ID, ATTEMPTS, RETRY_CEILING);

        verify(runRepository, never()).markStalled(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Гонка со вторым инстансом: markStalled ничего не обновил — метрика и лог не врут")
    void staysQuietWhenAnotherInstanceWonTheRace() {
        givenRun("pending", null);
        when(runRepository.markStalled(anyLong(), anyString(), anyString(), anyInt())).thenReturn(0);

        service.failStalledRun(RUN_ID, ATTEMPTS, RETRY_CEILING);

        verify(runRepository).markStalled(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Дочерний прогон без открытых стадий — в failed_stage уходит текущий статус")
    void fallsBackToCurrentStatusWhenNoStageIsOpen() {
        givenRun("transforming", "uid-42");
        when(stageLogRepository.findRunningStageNames(RUN_ID)).thenReturn(List.of());

        service.failStalledRun(RUN_ID, ATTEMPTS, RETRY_CEILING);

        verify(runRepository).markStalled(eq(RUN_ID), anyString(), eq("transforming"), anyInt());
    }

    private void givenRun(String status, String artifactUid) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setArtifactUid(artifactUid);
        run.setArtifactType("structurizr-sequence");
        run.setConfigurationId(1L);
        run.setStartedAt(LocalDateTime.now().minusDays(2));
        run.setResumeCount(ATTEMPTS);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
