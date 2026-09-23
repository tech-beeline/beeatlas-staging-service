package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.dto.usecase.UseCaseDraft;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
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
    private static final long RAW_DATA_REF_ID = 42L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UseCaseCanonicalRepository canonicalRepository;
    private UseCaseLandscapeRepository landscapeRepository;
    private PipelineRunService pipelineRunService;
    private UseCaseSaver saver;

    @BeforeEach
    void setUp() {
        canonicalRepository = mock(UseCaseCanonicalRepository.class);
        landscapeRepository = mock(UseCaseLandscapeRepository.class);
        pipelineRunService = mock(PipelineRunService.class);
        RunBranchResolver runBranchResolver = mock(RunBranchResolver.class);

        ArtifactBatch batch = new ArtifactBatch();
        batch.setId(15L);
        when(pipelineRunService.createBatch(anyString(), anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyInt()))
                .thenReturn(batch);
        when(runBranchResolver.resolve(RUN_ID)).thenReturn("design");
        when(canonicalRepository.findOrCreateUseCase("UC-001", "PRJ-1")).thenReturn(1L);
        when(canonicalRepository.insertUseCaseVersion(eq(1L), any(), eq(RUN_ID), eq("UC-001"), any(), any(),
                eq("design"), any())).thenReturn(10L);

        saver = new UseCaseSaver(canonicalRepository, landscapeRepository, pipelineRunService, runBranchResolver,
                objectMapper);
    }

    @Test
    @DisplayName("Фаза 1: смаппированный шаг связан с версией операции, несмаппированные — без связей")
    void writesStepsWithAndWithoutOperationLinks() throws Exception {
        when(landscapeRepository.findOperationByCode("/orders", "orders_api", "design"))
                .thenReturn(Optional.of(new LandscapeOperation(34L, "/orders", "orders_api", "api", "BC-2")));

        SaveResult result = saver.save("UC-001", "usecase", RAW_DATA_REF_ID, RUN_ID, snapshot());

        ArgumentCaptor<StepVersionRow> steps = ArgumentCaptor.forClass(StepVersionRow.class);
        verify(canonicalRepository, times(3)).insertStepVersion(steps.capture());
        assertThat(steps.getAllValues()).extracting(StepVersionRow::extUid,
                        StepVersionRow::calleeOperationVersionId, StepVersionRow::callStatus)
                .containsExactly(
                        Tuple.tuple("P-01", 34L, "confirmed"),
                        Tuple.tuple("P-02", null, null),
                        Tuple.tuple("P-03", null, null));
        assertThat(steps.getAllValues()).allSatisfy(step -> assertThat(step.branchName()).isEqualTo("design"));
        assertThat(result.summary()).containsEntry("batchId", 15L)
                .containsEntry("usecaseVersionId", 10L)
                .containsEntry("stepsSaved", 3)
                .containsEntry("unmapped", 2);
    }

    @Test
    @DisplayName("По каждой несмаппированной стороне пишется notice с причиной и вариантами решения")
    void writesNoticesForUnmappedSides() throws Exception {
        when(landscapeRepository.findOperationByCode("/orders", "orders_api", "design"))
                .thenReturn(Optional.of(new LandscapeOperation(34L, "/orders", "orders_api", "api", "BC-2")));

        saver.save("UC-001", "usecase", RAW_DATA_REF_ID, RUN_ID, snapshot());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArtifactNotice>> notices = ArgumentCaptor.forClass(List.class);
        verify(pipelineRunService).saveNotices(eq(RAW_DATA_REF_ID), notices.capture());
        assertThat(notices.getValue()).extracting(ArtifactNotice::code, ArtifactNotice::level,
                        ArtifactNotice::entityUid)
                .containsExactly(
                        Tuple.tuple(UseCaseSaver.UNMAPPED_SIDE, "warning", "P-02"),
                        Tuple.tuple(UseCaseSaver.UNMAPPED_SIDE, "warning", "P-03"));
    }

    @Test
    @DisplayName("Операция исчезла из ландшафта — шаг пишется без связи и попадает в контекст паузы")
    void leavesTheStepUnlinkedWhenTheOperationIsGone() throws Exception {
        when(landscapeRepository.findOperationByCode(anyString(), any(), anyString())).thenReturn(Optional.empty());

        SaveResult result = saver.save("UC-001", "usecase", RAW_DATA_REF_ID, RUN_ID, snapshot());

        ArgumentCaptor<StepVersionRow> steps = ArgumentCaptor.forClass(StepVersionRow.class);
        verify(canonicalRepository, times(3)).insertStepVersion(steps.capture());
        assertThat(steps.getAllValues().get(0).calleeOperationVersionId()).isNull();
        assertThat(steps.getAllValues().get(0).callStatus()).isNull();
        assertThat(result.summary()).containsEntry("unmapped", 3);
    }

    @Test
    @DisplayName("Пустой canonical_snapshot_json — запись невозможна")
    void failsWithoutASnapshot() {
        assertThatThrownBy(() -> saver.save("UC-001", "usecase", RAW_DATA_REF_ID, RUN_ID, "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canonical_snapshot_json");
        verify(canonicalRepository, never()).findOrCreateUseCase(anyString(), any());
    }

    private String snapshot() throws Exception {
        UseCaseDraft draft = new UseCaseDraft(
                new UseCaseDraft.Header("UC-001", "Онлайн-заказ", null, "PRJ-1"), "design",
                List.of(new UseCaseDraft.MappedPart("P-01", "interaction", 1, "main", "action", "POST /orders",
                        "BC-2", "api", "orders_api", "/orders", null, null, null, null, "confirmed")),
                List.of(new UseCaseDraft.UnmappedPart("P-02", "interaction", 2, "main", "action", "GET /users",
                                "callee", List.of("gw"), "не найдено", "map_existing | create_new"),
                        new UseCaseDraft.UnmappedPart("P-03", "interaction", 3, "main", "action", "POST /pay",
                                "callee", List.of("pay"), "не найдено", "map_existing | create_new")));
        return objectMapper.writeValueAsString(draft);
    }
}
