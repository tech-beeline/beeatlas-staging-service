package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.dto.usecase.UseCaseDraft;
import ru.beeline.staging.repository.ImportDecisionRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.RequiredOperationVersionRow;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.StepVersionRow;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeOperation;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.RunBranchResolver;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UseCaseSaverTest {

    private static final long RUN_ID = 7788L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PipelineRunRepository pipelineRunRepository;
    private ImportDecisionRepository importDecisionRepository;
    private UseCaseCanonicalRepository canonicalRepository;
    private UseCaseLandscapeRepository landscapeRepository;
    private UseCaseSaver saver;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        importDecisionRepository = mock(ImportDecisionRepository.class);
        canonicalRepository = mock(UseCaseCanonicalRepository.class);
        landscapeRepository = mock(UseCaseLandscapeRepository.class);
        PipelineRunService pipelineRunService = mock(PipelineRunService.class);
        RunBranchResolver runBranchResolver = mock(RunBranchResolver.class);

        ArtifactBatch batch = new ArtifactBatch();
        batch.setId(15L);
        when(pipelineRunService.createBatch(anyString(), anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyInt()))
                .thenReturn(batch);
        when(runBranchResolver.resolve(RUN_ID)).thenReturn("design");
        when(canonicalRepository.findOrCreateUseCase("UC-001", "PRJ-1")).thenReturn(1L);
        when(canonicalRepository.insertUseCaseVersion(eq(1L), any(), eq("UC-001"), any(), any(), eq("design"), any()))
                .thenReturn(10L);
        when(canonicalRepository.findOrCreateRequiredOperation(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(100L);
        when(canonicalRepository.insertRequiredOperationVersion(any())).thenReturn(200L);

        saver = new UseCaseSaver(pipelineRunRepository, importDecisionRepository, canonicalRepository,
                landscapeRepository, pipelineRunService, runBranchResolver, objectMapper);
    }

    @Test
    @DisplayName("Mapped-часть — confirmed + matched, map_existing — architect_specified, create_new — planned/required")
    void appliesDraftAndDecisions() throws Exception {
        givenRunWithDraft();
        when(landscapeRepository.findOperationByCode("/orders", "orders_api", "design"))
                .thenReturn(Optional.of(new LandscapeOperation(34L, "/orders", "orders_api", "api", "BC-2")));
        when(importDecisionRepository.findByRunId(RUN_ID)).thenReturn(List.of(
                new ImportDecision(1L, RUN_ID, "P-02", "map_existing",
                        "{\"containerCode\":\"gw.BC-1\",\"interfaceCode\":\"users_api.gw.BC-1\"}", null),
                new ImportDecision(2L, RUN_ID, "P-03", "create_new", null,
                        "{\"productCode\":\"BC-9\",\"containerName\":\"Payment Adapter\",\"interfaceName\":\"payments_api\",\"protocol\":\"REST\"}")));

        SaveResult result = saver.save("UC-001", "usecase", 42L, RUN_ID, null);

        ArgumentCaptor<StepVersionRow> steps = ArgumentCaptor.forClass(StepVersionRow.class);
        verify(canonicalRepository, times(3)).insertStepVersion(steps.capture());
        assertThat(steps.getAllValues()).extracting(StepVersionRow::extUid, StepVersionRow::callStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("P-01", "confirmed"),
                        org.assertj.core.groups.Tuple.tuple("P-02", "architect_specified"),
                        org.assertj.core.groups.Tuple.tuple("P-03", "planned"));
        assertThat(steps.getAllValues()).allSatisfy(step -> assertThat(step.branchName()).isEqualTo("design"));

        ArgumentCaptor<RequiredOperationVersionRow> requirements = ArgumentCaptor.forClass(RequiredOperationVersionRow.class);
        verify(canonicalRepository, times(3)).insertRequiredOperationVersion(requirements.capture());
        assertThat(requirements.getAllValues()).extracting(RequiredOperationVersionRow::status,
                        RequiredOperationVersionRow::operationVersionId, RequiredOperationVersionRow::productUid)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("matched", 34L, "BC-2"),
                        org.assertj.core.groups.Tuple.tuple("required", null, "gw.BC-1"),
                        org.assertj.core.groups.Tuple.tuple("required", null, "BC-9"));
        verify(canonicalRepository).findOrCreateRequiredOperation("1:operation:/orders", 1L, "/orders", "operation");
        verify(canonicalRepository).findOrCreateRequiredOperation("1:interface:payments_api", 1L, "payments_api", "interface");

        assertThat(result.summary()).containsEntry("batchId", 15L).containsEntry("stepsSaved", 3)
                .containsEntry("requiredOperationsCreated", 1);
    }

    @Test
    @DisplayName("Несмаппированная часть без решения — применение падает, шаги не пишутся")
    void failsWithoutDecision() {
        givenRunWithDraft();
        when(importDecisionRepository.findByRunId(RUN_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> saver.save("UC-001", "usecase", 42L, RUN_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("P-02");
    }

    @Test
    @DisplayName("Пустой draft_json — применение невозможно")
    void failsWithoutDraft() {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> saver.save("UC-001", "usecase", 42L, RUN_ID, null))
                .isInstanceOf(IllegalStateException.class);
        verify(canonicalRepository, never()).findOrCreateUseCase(anyString(), any());
    }

    private void givenRunWithDraft() {
        UseCaseDraft draft = new UseCaseDraft(
                new UseCaseDraft.Header("UC-001", "Онлайн-заказ", null, "PRJ-1"), "design",
                List.of(new UseCaseDraft.MappedPart("P-01", "interaction", 1, "main", "action", "POST /orders",
                        "BC-2", "api", "orders_api", "/orders", null, null, null, null, "confirmed")),
                List.of(new UseCaseDraft.UnmappedPart("P-02", "interaction", 2, "main", "action", "GET /users",
                                "callee", List.of("gw"), "не найдено", "map_existing | create_new"),
                        new UseCaseDraft.UnmappedPart("P-03", "interaction", 3, "main", "action", "POST /pay",
                                "callee", List.of("pay"), "не найдено", "map_existing | create_new")));
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        try {
            run.setDraftJson(objectMapper.writeValueAsString(draft));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
