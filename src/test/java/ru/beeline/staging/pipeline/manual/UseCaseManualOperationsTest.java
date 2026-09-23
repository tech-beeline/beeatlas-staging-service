package ru.beeline.staging.pipeline.manual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.canonical.OperationEntity;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.StepRow;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.UseCaseVersionRow;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeInterface;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeOperation;
import ru.beeline.staging.repository.canonical.OperationRepository;
import ru.beeline.staging.repository.canonical.OperationVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.util.List;
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
import static org.mockito.Mockito.when;

class UseCaseManualOperationsTest {

    private static final long RUN_ID = 7788L;
    private static final long RAW_DATA_REF_ID = 42L;
    private static final long USECASE_VERSION_ID = 10L;

    private UseCaseCanonicalRepository canonicalRepository;
    private UseCaseLandscapeRepository landscapeRepository;
    private OperationRepository operationRepository;
    private OperationVersionRepository operationVersionRepository;
    private ArtifactNoticeService noticeService;
    private UseCaseManualOperations manualOperations;

    @BeforeEach
    void setUp() {
        canonicalRepository = mock(UseCaseCanonicalRepository.class);
        landscapeRepository = mock(UseCaseLandscapeRepository.class);
        operationRepository = mock(OperationRepository.class);
        operationVersionRepository = mock(OperationVersionRepository.class);
        noticeService = mock(ArtifactNoticeService.class);
        PipelineRunRepository pipelineRunRepository = mock(PipelineRunRepository.class);

        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setArtifactType("usecase");
        run.setArtifactUid("UC-001");
        run.setRawDataRefId(RAW_DATA_REF_ID);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(canonicalRepository.findVersionByRunId(RUN_ID)).thenReturn(Optional.of(new UseCaseVersionRow(
                USECASE_VERSION_ID, 1L, "UC-001", "Онлайн-заказ", "PRJ-1", "design",
                "{\"bi_step_code\":\"Step.00.00.02.08\"}")));
        when(canonicalRepository.findSteps(USECASE_VERSION_ID)).thenReturn(List.of(mappedStep(), unmappedStep()));

        manualOperations = new UseCaseManualOperations(canonicalRepository, landscapeRepository, operationRepository,
                operationVersionRepository, pipelineRunRepository, noticeService, new ObjectMapper());
    }

    @Test
    @DisplayName("Контекст паузы собирается из канона: шаги без связи с операцией — unmapped")
    void buildsThePauseContextFromTheCanonicalModel() {
        JsonNode context = manualOperations.pauseContext(RUN_ID);

        assertThat(context.path("usecase").path("code").asText()).isEqualTo("UC-001");
        assertThat(context.path("usecase").path("biStepCode").asText()).isEqualTo("Step.00.00.02.08");
        assertThat(context.path("branch").asText()).isEqualTo("design");
        assertThat(context.path("mapped")).hasSize(1);
        assertThat(context.path("mapped").get(0).path("partId").asText()).isEqualTo("P-01");
        assertThat(context.path("mapped").get(0).path("operation").asText()).isEqualTo("/orders");
        assertThat(context.path("mapped").get(0).path("system").asText()).isEqualTo("BC-2");
        assertThat(context.path("unmapped")).hasSize(1);
        assertThat(context.path("unmapped").get(0).path("partId").asText()).isEqualTo("P-02");
        assertThat(context.path("unmapped").get(0).path("reason").asText()).isEqualTo("не найдено");
        assertThat(context.path("unmapped").get(0).path("participants").get(0).asText()).isEqualTo("gw");
    }

    @Test
    @DisplayName("Несмаппированные части — шаги канона без связи с операцией")
    void listsUnmappedPartsFromTheCanonicalModel() {
        assertThat(manualOperations.unmappedParts(RUN_ID)).containsExactly("P-02");
    }

    @Test
    @DisplayName("Запуск без записанной версии UseCase — контекста паузы нет")
    void returnsNoContextBeforeThePhaseOneWrite() {
        when(canonicalRepository.findVersionByRunId(RUN_ID)).thenReturn(Optional.empty());

        assertThat(manualOperations.pauseContext(RUN_ID)).isNull();
        assertThat(manualOperations.unmappedParts(RUN_ID)).isEmpty();
    }

    @Test
    @DisplayName("map_existing связывает шаг с версией операции in-place, статус вызова — architect_specified")
    void appliesMapExistingInPlace() {
        when(landscapeRepository.findOperationsByInterface("users_api.gw.BC-1", "gw.BC-1", "design", 2))
                .thenReturn(List.of(new LandscapeOperation(55L, "/users", "users_api.gw.BC-1", "gw.BC-1", "BC-1")));

        manualOperations.applyDecision(RUN_ID, decision(ImportDecision.MAP_EXISTING,
                "{\"containerCode\":\"gw.BC-1\",\"interfaceCode\":\"users_api.gw.BC-1\"}", null));

        ArgumentCaptor<String> patch = ArgumentCaptor.forClass(String.class);
        verify(canonicalRepository).updateStepCallee(eq(2L), eq(55L), eq("architect_specified"), patch.capture());
        assertThat(patch.getValue()).contains("users_api.gw.BC-1");
        assertThat(savedNotice().code()).isEqualTo("usecase.saver.decision.map_existing");
    }

    @Test
    @DisplayName("Несколько операций у интерфейса без operationCode — 400 и notice apply_failed")
    void rejectsAnAmbiguousMapExisting() {
        when(landscapeRepository.findOperationsByInterface("users_api.gw.BC-1", "gw.BC-1", "design", 2))
                .thenReturn(List.of(
                        new LandscapeOperation(55L, "/users", "users_api.gw.BC-1", "gw.BC-1", "BC-1"),
                        new LandscapeOperation(56L, "/users/{id}", "users_api.gw.BC-1", "gw.BC-1", "BC-1")));

        assertThatThrownBy(() -> manualOperations.applyDecision(RUN_ID, decision(ImportDecision.MAP_EXISTING,
                "{\"containerCode\":\"gw.BC-1\",\"interfaceCode\":\"users_api.gw.BC-1\"}", null)))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("operationCode");

        verify(canonicalRepository, never()).updateStepCallee(anyLong(), anyLong(), anyString(), anyString());
        assertThat(failureNotice().code()).isEqualTo("usecase.saver.decision.apply_failed");
    }

    @Test
    @DisplayName("create_new создаёт операцию и версию в ветке запуска, статус вызова — planned")
    void appliesCreateNew() {
        when(landscapeRepository.findInterface("payments_api", "Payment Adapter", "design"))
                .thenReturn(Optional.of(new LandscapeInterface(77L, "payments_api", "Payment Adapter", "BC-9")));
        when(operationRepository.findByUid("payments_api.POST /pay")).thenReturn(Optional.empty());
        when(operationRepository.save(any(OperationEntity.class))).thenAnswer(invocation -> {
            OperationEntity entity = invocation.getArgument(0);
            entity.setId(99L);
            return entity;
        });
        when(operationVersionRepository.save(any(OperationVersion.class))).thenAnswer(invocation -> {
            OperationVersion version = invocation.getArgument(0);
            version.setId(101L);
            return version;
        });

        manualOperations.applyDecision(RUN_ID, decision(ImportDecision.CREATE_NEW, null,
                "{\"productCode\":\"BC-9\",\"containerName\":\"Payment Adapter\","
                        + "\"interfaceName\":\"payments_api\",\"protocol\":\"REST\"}"));

        ArgumentCaptor<OperationVersion> version = ArgumentCaptor.forClass(OperationVersion.class);
        verify(operationVersionRepository).save(version.capture());
        assertThat(version.getValue().getOperationId()).isEqualTo(99L);
        assertThat(version.getValue().getInterfaceVersionId()).isEqualTo(77L);
        assertThat(version.getValue().getBranchName()).isEqualTo("design");
        assertThat(version.getValue().getJsonData()).contains("BC-9").contains("REST");
        verify(canonicalRepository).updateStepCallee(eq(2L), eq(101L), eq("planned"), anyString());
        assertThat(savedNotice().code()).isEqualTo("usecase.saver.decision.create_new");
    }

    @Test
    @DisplayName("Решение по части, которой нет в каноне — 404")
    void rejectsAnUnknownPart() {
        assertThatThrownBy(() -> manualOperations.applyDecision(RUN_ID,
                new ImportDecision(null, RUN_ID, "P-99", ImportDecision.MAP_EXISTING, "{}", null)))
                .isInstanceOf(ru.beeline.staging.exception.PipelineRunNotFoundException.class);
    }

    private ImportDecision decision(String type, String targetJson, String newRequestJson) {
        return new ImportDecision(null, RUN_ID, "P-02", type, targetJson, newRequestJson);
    }

    private ArtifactNotice failureNotice() {
        ArgumentCaptor<ArtifactNotice> notice = ArgumentCaptor.forClass(ArtifactNotice.class);
        verify(noticeService).saveNoticeInNewTransaction(eq(RAW_DATA_REF_ID), notice.capture());
        return notice.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArtifactNotice savedNotice() {
        ArgumentCaptor<List<ArtifactNotice>> notices = ArgumentCaptor.forClass(List.class);
        verify(noticeService).saveNotices(eq(RAW_DATA_REF_ID), notices.capture());
        return notices.getValue().get(0);
    }

    private static StepRow mappedStep() {
        return new StepRow(1L, "P-01", "POST /orders", 1, "main", "confirmed", "action", null, 34L,
                "{\"operation_code\":\"/orders\",\"tc_code\":\"TC-17\"}", "/orders", "orders_api", "api", "BC-2",
                null, null, null, null);
    }

    private static StepRow unmappedStep() {
        return new StepRow(2L, "P-02", "POST /pay", 2, "main", null, "action", null, null,
                "{\"reason\":\"не найдено\",\"participants\":[\"gw\"],\"suggestion\":\"map_existing | create_new\"}",
                null, null, null, null, null, null, null, null);
    }
}
