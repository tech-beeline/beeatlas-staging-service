package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.canonical.ContainerVersion;
import ru.beeline.staging.domain.canonical.InterfaceVersion;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.domain.canonical.ProductVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot;
import ru.beeline.staging.pipeline.transformer.UseCaseSnapshot;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.StepVersionRow;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.RunBranchResolver;

import java.util.List;
import java.util.Map;

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
    private static final String MATCHED_UID = "op-matched";
    private static final String UNMATCHED_UID = "op-unmatched";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UseCaseCanonicalRepository canonicalRepository;
    private OperationMatchService operationMatchService;
    private PipelineRunService pipelineRunService;
    private UseCaseSaver saver;

    @BeforeEach
    void setUp() {
        canonicalRepository = mock(UseCaseCanonicalRepository.class);
        UseCaseLandscapeRepository landscapeRepository = mock(UseCaseLandscapeRepository.class);
        ProductMatchService productMatchService = mock(ProductMatchService.class);
        ContainerMatchService containerMatchService = mock(ContainerMatchService.class);
        InterfaceMatchService interfaceMatchService = mock(InterfaceMatchService.class);
        operationMatchService = mock(OperationMatchService.class);
        pipelineRunService = mock(PipelineRunService.class);
        RunBranchResolver runBranchResolver = mock(RunBranchResolver.class);

        ArtifactBatch batch = new ArtifactBatch();
        batch.setId(15L);
        when(pipelineRunService.createBatch(anyString(), anyString(), anyLong(), anyLong(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt())).thenReturn(batch);
        when(runBranchResolver.resolve(RUN_ID)).thenReturn("design");
        when(canonicalRepository.findOrCreateUseCase("UC-001", "PRJ-1")).thenReturn(1L);
        when(canonicalRepository.insertUseCaseVersion(eq(1L), any(), eq(RUN_ID), eq("UC-001"), any(), any(),
                eq("design"), any())).thenReturn(10L);
        when(productMatchService.matchOrCreate(anyString(), any(), any(), any(), any(), any(), anyLong(), any(),
                anyString())).thenReturn(new ProductVersion());
        when(containerMatchService.matchOrCreate(anyString(), any(), any(), any(), any(), any(), any(), any(),
                anyLong(), any(), anyString())).thenReturn(new ContainerVersion());
        when(interfaceMatchService.matchOrCreate(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyLong(), any(), anyString())).thenReturn(new InterfaceVersion());
        when(operationMatchService.matchOrCreate(eq(MATCHED_UID), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyLong(), any(), anyString(), any(), any(), any()))
                .thenReturn(operationVersion(101L, 4242));
        when(operationMatchService.matchOrCreate(eq(UNMATCHED_UID), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyLong(), any(), anyString(), any(), any(), any()))
                .thenReturn(operationVersion(102L, null));

        saver = new UseCaseSaver(canonicalRepository, landscapeRepository, productMatchService, containerMatchService,
                interfaceMatchService, operationMatchService, pipelineRunService, runBranchResolver, objectMapper);
    }

    @Test
    @DisplayName("Фаза 1: шаг всегда связан со своей операцией; сопоставленный получает call_status=confirmed")
    void linksEveryStepToItsOperation() throws Exception {
        SaveResult result = saver.save("UC-001", "usecase", RAW_DATA_REF_ID, RUN_ID, snapshot());

        ArgumentCaptor<StepVersionRow> steps = ArgumentCaptor.forClass(StepVersionRow.class);
        verify(canonicalRepository, times(2)).insertStepVersion(steps.capture());
        assertThat(steps.getAllValues()).extracting(StepVersionRow::extUid,
                        StepVersionRow::calleeOperationVersionId, StepVersionRow::callStatus)
                .containsExactly(
                        Tuple.tuple("P-01", 101L, "confirmed"),
                        Tuple.tuple("P-02", 102L, null));
        assertThat(steps.getAllValues()).allSatisfy(step -> assertThat(step.branchName()).isEqualTo("design"));
        assertThat(steps.getAllValues().get(1).operationVersionId()).isEqualTo(101L);
        assertThat(result.summary()).containsEntry("batchId", 15L)
                .containsEntry("usecaseVersionId", 10L)
                .containsEntry("stepsSaved", 2)
                .containsEntry("unmatched", 1);
    }

    @Test
    @DisplayName("Несопоставленный шаг получает notice с причиной и вариантами решения")
    void writesNoticeForUnmatchedStep() throws Exception {
        saver.save("UC-001", "usecase", RAW_DATA_REF_ID, RUN_ID, snapshot());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArtifactNotice>> notices = ArgumentCaptor.forClass(List.class);
        verify(pipelineRunService).saveNotices(eq(RAW_DATA_REF_ID), notices.capture());
        assertThat(notices.getValue()).singleElement().satisfies(notice -> {
            assertThat(notice.code()).isEqualTo(UseCaseSaver.UNMAPPED_SIDE);
            assertThat(notice.level()).isEqualTo("warning");
            assertThat(notice.entityUid()).isEqualTo("P-02");
            assertThat(notice.details()).contains("map_existing | planned");
        });
    }

    @Test
    @DisplayName("Операции снапшота уходят в канон вместе с connectionOperationId и connectionInterfaceId")
    void savesOperationsWithArchLink() throws Exception {
        saver.save("UC-001", "usecase", RAW_DATA_REF_ID, RUN_ID, snapshot());

        verify(operationMatchService).matchOrCreate(eq(MATCHED_UID), eq(MATCHED_UID), eq("/orders"), eq("POST"),
                any(), any(), any(), any(), any(), any(), any(), any(), eq(RAW_DATA_REF_ID), eq(15L), eq("design"),
                eq(4242), eq(7), any());
    }

    @Test
    @DisplayName("Пустой canonical_snapshot_json — запись невозможна")
    void failsWithoutASnapshot() {
        assertThatThrownBy(() -> saver.save("UC-001", "usecase", RAW_DATA_REF_ID, RUN_ID, "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canonical_snapshot_json");
        verify(canonicalRepository, never()).findOrCreateUseCase(anyString(), any());
    }

    private static OperationVersion operationVersion(Long id, Integer connectionOperationId) {
        OperationVersion version = new OperationVersion();
        version.setId(id);
        version.setConnectionOperationId(connectionOperationId);
        return version;
    }

    private String snapshot() throws Exception {
        UseCaseSnapshot snapshot = new UseCaseSnapshot();
        UseCaseSnapshot.Header header = new UseCaseSnapshot.Header();
        header.setCode("UC-001");
        header.setName("Онлайн-заказ");
        header.setProjectCode("PRJ-1");
        snapshot.setUsecase(header);
        snapshot.setBranch("design");

        E2ESequenceSnapshot entities = snapshot.getEntities();
        entities.getProducts().add(product("BC-2"));
        entities.getContainers().add(container("BC-2"));
        entities.getInterfaces().add(anInterface("iface-1", "BC-2"));
        entities.getOperations().add(operation(MATCHED_UID, "iface-1", "/orders", "POST", 4242, 7,
                Map.of("productAlias", "BC-2")));
        entities.getOperations().add(operation(UNMATCHED_UID, "iface-1", "/status", "GET", null, null, null));

        snapshot.getSteps().add(step("P-01", 1, MATCHED_UID, null, "POST", "/orders", null));
        snapshot.getSteps().add(step("P-02", 2, UNMATCHED_UID, MATCHED_UID, "GET", "/status",
                "Операция GET /status не найдена в архитектуре продукта BC-2"));
        return objectMapper.writeValueAsString(snapshot);
    }

    private static E2ESequenceSnapshot.ProductDraft product(String uid) {
        E2ESequenceSnapshot.ProductDraft draft = new E2ESequenceSnapshot.ProductDraft();
        draft.setUid(uid);
        draft.setExtUid(uid);
        draft.setName(uid);
        return draft;
    }

    private static E2ESequenceSnapshot.ContainerDraft container(String uid) {
        E2ESequenceSnapshot.ContainerDraft draft = new E2ESequenceSnapshot.ContainerDraft();
        draft.setUid(uid);
        draft.setExtUid(uid);
        draft.setProductUid(uid);
        draft.setName(uid);
        return draft;
    }

    private static E2ESequenceSnapshot.InterfaceDraft anInterface(String uid, String containerUid) {
        E2ESequenceSnapshot.InterfaceDraft draft = new E2ESequenceSnapshot.InterfaceDraft();
        draft.setUid(uid);
        draft.setExtUid(uid);
        draft.setContainerUid(containerUid);
        draft.setProtocol("UNKNOWN");
        draft.setName(uid);
        return draft;
    }

    private static E2ESequenceSnapshot.OperationDraft operation(String extUid, String interfaceUid, String name,
                                                                String type, Integer connectionOperationId,
                                                                Integer connectionInterfaceId,
                                                                Map<String, Object> matched) {
        E2ESequenceSnapshot.OperationDraft draft = new E2ESequenceSnapshot.OperationDraft();
        draft.setExtUid(extUid);
        draft.setInterfaceUid(interfaceUid);
        draft.setName(name);
        draft.setType(type);
        draft.setConnectionOperationId(connectionOperationId);
        draft.setConnectionInterfaceId(connectionInterfaceId);
        draft.setMatchedOperation(matched);
        return draft;
    }

    private static UseCaseSnapshot.Step step(String partId, int seq, String calleeExtUid, String callerExtUid,
                                             String type, String name, String reason) {
        UseCaseSnapshot.Step step = new UseCaseSnapshot.Step();
        step.setPartId(partId);
        step.setSeq(seq);
        step.setScenarioType("main");
        step.setStepType("action");
        step.setName(type + " " + name);
        step.setCalleeOperationExtUid(calleeExtUid);
        step.setCallerOperationExtUid(callerExtUid);
        step.setProductAlias("BC-2");
        step.setInterfaceCode("iface-1");
        step.setOperationType(type);
        step.setOperationName(name);
        step.setReason(reason);
        return step;
    }
}
