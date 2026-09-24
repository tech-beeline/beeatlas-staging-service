package ru.beeline.staging.pipeline.exec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.pipeline.PipelineDefinitions;
import ru.beeline.staging.pipeline.manual.ManualOperations;
import ru.beeline.staging.pipeline.publisher.ArtifactPublisher;
import ru.beeline.staging.pipeline.publisher.E2ePublisher;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManualAndPublisherStageTest {

    private static final long RUN_ID = 77L;

    private final PipelineDefinitions pipelineDefinitions = new PipelineDefinitions();
    private PipelineRunService pipelineRunService;
    private PipelineRunRepository pipelineRunRepository;
    private ManualOperations manualOperations;

    @BeforeEach
    void setUp() {
        pipelineRunService = mock(PipelineRunService.class);
        pipelineRunRepository = mock(PipelineRunRepository.class);
        manualOperations = mock(ManualOperations.class);
        when(pipelineRunService.startStage(anyLong(), anyString(), any())).thenReturn(5L);
        when(manualOperations.reviewable(anyString(), anyLong())).thenReturn(true);
    }

    @Test
    @DisplayName("Стадия manual ставит e2e-plantuml на паузу решения пользователя")
    void manualStagePausesThePlantUmlRun() {
        givenRun("e2e-plantuml", "saving");

        manualStage().execute(RUN_ID);

        verify(pipelineRunRepository).pause(RUN_ID, PipelineDefinitions.PAUSE_STATUS);
        verify(pipelineRunService).completeStage(5L, "decision=awaited", Map.of("awaitingReview", true, "unmapped", 0));
    }

    @Test
    @DisplayName("После apply стадия manual пропускает паузу")
    void manualStageLetsAnAppliedRunThrough() {
        givenRun("e2e-plantuml", "applying");

        manualStage().execute(RUN_ID);

        verify(pipelineRunRepository, never()).pause(anyLong(), anyString());
        verify(pipelineRunService).completeStage(5L, "decision=accepted", null);
    }

    @Test
    @DisplayName("Прогон ничего не записал в канон — пауза не нужна, цепочка идёт дальше")
    void manualStageSkipsThePauseWhenThereIsNothingToReview() {
        givenRun("usecase", "saving");
        when(manualOperations.reviewable("usecase", RUN_ID)).thenReturn(false);

        manualStage().execute(RUN_ID);

        verify(pipelineRunRepository, never()).pause(anyLong(), anyString());
        verify(pipelineRunRepository).advanceStage(RUN_ID, "saving");
        verify(pipelineRunService).completeStage(5L, "decision=not_required", null);
    }

    @Test
    @DisplayName("Для типа без стадии manual ничего не происходит")
    void manualStageIsANoOpForTypesWithoutIt() {
        givenRun("metric-queries", "saving");

        manualStage().execute(RUN_ID);

        verifyNoInteractions(pipelineRunService);
        verify(pipelineRunRepository, never()).pause(anyLong(), anyString());
    }

    @Test
    @DisplayName("Стадия publisher публикует артефакт и завершает прогон")
    void publisherStagePublishesAndCompletesTheRun() throws Exception {
        givenRun("e2e-plantuml", "publishing");
        ArtifactPublisher publisher = mock(ArtifactPublisher.class);
        when(publisher.moduleCode()).thenReturn(E2ePublisher.MODULE_CODE);
        when(publisher.publish(eq("E2E-001"), eq("e2e-plantuml"), eq(42L), eq(RUN_ID)))
                .thenReturn(Map.of("published", true));

        publisherStage(publisher).execute(RUN_ID);

        verify(publisher).publish("E2E-001", "e2e-plantuml", 42L, RUN_ID);
        verify(pipelineRunService).completeRun(RUN_ID);
    }

    @Test
    @DisplayName("Отсутствие модуля публикации роняет стадию, а не тихо завершает прогон")
    void publisherStageFailsWhenTheModuleIsMissing() {
        givenRun("e2e-plantuml", "publishing");
        ArtifactPublisher other = mock(ArtifactPublisher.class);
        when(other.moduleCode()).thenReturn("some-other-publisher");

        assertThatThrownBy(() -> publisherStage(other).execute(RUN_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ArtifactPublisher registered");

        verify(pipelineRunService, never()).completeRun(anyLong());
        verify(pipelineRunService).failStage(eq(5L), eq(RUN_ID), eq("publisher"), anyString());
    }

    @Test
    @DisplayName("Порядок стадий включает manual и publisher после saver")
    void stageOrderEndsWithManualAndPublisher() {
        assertThat(PipelineDefinitions.STAGE_ORDER)
                .containsExactly("pre-adapter", "adapter", "validator", "transformer", "saver", "manual", "publisher");
        assertThat(pipelineDefinitions.hasStage("e2e-plantuml", "manual")).isTrue();
        assertThat(pipelineDefinitions.hasStage("e2e-sequence", "manual")).isFalse();
        assertThat(pipelineDefinitions.hasStage("usecase", "manual")).isTrue();
        assertThat(pipelineDefinitions.moduleMapFor("e2e-plantuml").get("publisher"))
                .isEqualTo(E2ePublisher.MODULE_CODE);
    }

    private void givenRun(String artifactType, String status) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setArtifactUid("E2E-001");
        run.setArtifactType(artifactType);
        run.setStatus(status);
        run.setRawDataRefId(42L);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }

    private ManualStage manualStage() {
        return new ManualStage(pipelineDefinitions, manualOperations, pipelineRunService, pipelineRunRepository);
    }

    private PublisherStage publisherStage(ArtifactPublisher publisher) {
        ModuleResolver moduleResolver = new ModuleResolver(pipelineDefinitions);
        PublisherStage stage = new PublisherStage(List.of(publisher), moduleResolver, pipelineRunService,
                pipelineRunRepository);
        stage.init();
        return stage;
    }
}
