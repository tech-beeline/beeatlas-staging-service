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
import ru.beeline.staging.repository.ImportDecisionRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;

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
    private static final String USECASE_DRAFT = """
            {"unmapped":[{"partId":"step-1","side":"callee"}]}
            """;

    private PipelineRunRepository pipelineRunRepository;
    private ImportDecisionRepository importDecisionRepository;
    private PipelineExecutionService pipelineExecutionService;
    private PipelineHitlService service;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        importDecisionRepository = mock(ImportDecisionRepository.class);
        pipelineExecutionService = mock(PipelineExecutionService.class);
        service = new PipelineHitlService(pipelineRunRepository, importDecisionRepository,
                pipelineExecutionService, new SimpleMeterRegistry(), new ObjectMapper(),
                mock(UseCaseLandscapeRepository.class), mock(RunBranchResolver.class));
        when(importDecisionRepository.findByRunId(RUN_ID)).thenReturn(List.of());
        when(pipelineRunRepository.markApplying(RUN_ID)).thenReturn(1);
    }

    @Test
    @DisplayName("e2e-plantuml применяется без контекста паузы — draft у него пустой")
    void appliesAPlantUmlRunWithoutADraft() {
        givenRun("e2e-plantuml", null);

        assertThat(service.apply(RUN_ID, null).status()).isEqualTo("applying");

        verify(pipelineRunRepository).markApplying(RUN_ID);
        verify(pipelineExecutionService).submitArtifactChain(RUN_ID, "e2e-plantuml", "e2e-plantuml");
    }

    @Test
    @DisplayName("Пустая строка в draft_json тоже не мешает применить")
    void treatsABlankDraftAsAbsent() {
        givenRun("e2e-plantuml", "   ");

        assertThat(service.apply(RUN_ID, "ok").status()).isEqualTo("applying");
    }

    @Test
    @DisplayName("UseCase с нерешёнными частями по-прежнему не применяется")
    void stillRejectsUseCaseWithUnresolvedParts() {
        givenRun("usecase", USECASE_DRAFT);

        assertThatThrownBy(() -> service.apply(RUN_ID, null))
                .isInstanceOf(PipelineRunUnresolvedPartsException.class);
        verify(pipelineRunRepository, org.mockito.Mockito.never()).markApplying(anyLong());
    }

    @Test
    @DisplayName("UseCase с решёнными частями применяется")
    void appliesUseCaseOnceEveryPartIsDecided() {
        givenRun("usecase", USECASE_DRAFT);
        when(importDecisionRepository.findByRunId(RUN_ID))
                .thenReturn(List.of(new ImportDecision(null, RUN_ID, "step-1", ImportDecision.MAP_EXISTING, null, null)));

        assertThat(service.apply(RUN_ID, null).status()).isEqualTo("applying");
    }

    @Test
    @DisplayName("Решения по частям без контекста паузы остаются конфликтом")
    void keepsDecisionsStrictWithoutADraft() {
        givenRun("e2e-plantuml", null);

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
        decision.setType(ImportDecision.CREATE_NEW);
        decision.setNewRequest(new ObjectMapper().createObjectNode()
                .put("productCode", "fdmshowcaseapp")
                .put("containerName", "Product Service")
                .put("interfaceName", "API")
                .put("protocol", "REST"));
        request.setDecisions(List.of(decision));
        return request;
    }

    private void givenRun(String artifactType, String draftJson) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setArtifactType(artifactType);
        run.setStatus("awaiting_review");
        run.setDraftJson(draftJson);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
