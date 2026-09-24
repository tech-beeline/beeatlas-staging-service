package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunUnresolvedPartsException;
import ru.beeline.staging.pipeline.manual.ManualOperations;
import ru.beeline.staging.repository.ImportDecisionRepository;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunApplyTest {

    private static final long RUN_ID = 77L;

    private PipelineRunRepository pipelineRunRepository;
    private ImportDecisionRepository importDecisionRepository;
    private PipelineExecutionService pipelineExecutionService;
    private ManualOperations manualOperations;
    private PipelineHitlService service;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        importDecisionRepository = mock(ImportDecisionRepository.class);
        pipelineExecutionService = mock(PipelineExecutionService.class);
        manualOperations = mock(ManualOperations.class);
        service = new PipelineHitlService(pipelineRunRepository, importDecisionRepository,
                pipelineExecutionService, new SimpleMeterRegistry(), manualOperations);
        when(importDecisionRepository.findByRunId(RUN_ID)).thenReturn(List.of());
        when(pipelineRunRepository.markApplying(RUN_ID)).thenReturn(1);
    }

    @Test
    @DisplayName("e2e-plantuml применяется без контекста паузы — ручных решений у него нет")
    void appliesAPlantUmlRunWithoutAPauseContext() {
        givenRun("e2e-plantuml", List.of());

        assertThat(service.apply(RUN_ID, null).status()).isEqualTo("applying");

        verify(pipelineRunRepository).markApplying(RUN_ID);
        verify(pipelineExecutionService).submitArtifactChain(RUN_ID, "e2e-plantuml", "e2e-plantuml");
    }

    @Test
    @DisplayName("UseCase с несмаппированными шагами в каноне не применяется")
    void stillRejectsUseCaseWithUnresolvedParts() {
        givenRun("usecase", List.of("step-1"));

        assertThatThrownBy(() -> service.apply(RUN_ID, null))
                .isInstanceOf(PipelineRunUnresolvedPartsException.class);
        verify(pipelineRunRepository, org.mockito.Mockito.never()).markApplying(anyLong());
    }

    @Test
    @DisplayName("UseCase с решёнными частями применяется")
    void appliesUseCaseOnceEveryPartIsDecided() {
        givenRun("usecase", List.of("step-1"));
        when(importDecisionRepository.findByRunId(RUN_ID))
                .thenReturn(List.of(new ImportDecision(null, RUN_ID, "step-1", ImportDecision.MAP_EXISTING, null, null)));

        assertThat(service.apply(RUN_ID, null).status()).isEqualTo("applying");
    }

    @Test
    @DisplayName("Связи шагов, заполненные фазой 2, снимают блокировку apply")
    void appliesUseCaseOnceCanonicalLinksAreFilled() {
        givenRun("usecase", List.of());

        assertThat(service.apply(RUN_ID, null).status()).isEqualTo("applying");
    }

    @Test
    @DisplayName("Решения по частям без контекста паузы остаются конфликтом")
    void keepsDecisionsStrictWithoutAPauseContext() {
        givenRun("e2e-plantuml", List.of());

        assertThatThrownBy(() -> service.decide(RUN_ID, decisionsRequest()))
                .isInstanceOf(PipelineRunConflictException.class)
                .hasMessageContaining("нет контекста паузы");
    }

    private ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsRequest decisionsRequest() {
        ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsRequest request =
                new ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsRequest();
        ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsRequest.Decision decision =
                new ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsRequest.Decision();
        decision.setPartId("step-1");
        decision.setType(ImportDecision.PLANNED);
        decision.setTarget(new ObjectMapper().createObjectNode().put("stepVersionId", 1));
        request.setDecisions(List.of(decision));
        return request;
    }

    private void givenRun(String artifactType, List<String> unmappedParts) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setArtifactType(artifactType);
        run.setStatus("awaiting_review");
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(manualOperations.unmappedParts(artifactType, RUN_ID)).thenReturn(unmappedParts);
    }
}
