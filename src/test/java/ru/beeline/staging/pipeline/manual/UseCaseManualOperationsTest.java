package ru.beeline.staging.pipeline.manual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.StepRow;
import ru.beeline.staging.repository.UseCaseCanonicalRepository.UseCaseVersionRow;
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
    private OperationVersionRepository operationVersionRepository;
    private ArtifactNoticeService noticeService;
    private UseCaseManualOperations manualOperations;

    @BeforeEach
    void setUp() {
        canonicalRepository = mock(UseCaseCanonicalRepository.class);
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
        when(canonicalRepository.findSteps(USECASE_VERSION_ID)).thenReturn(List.of(matchedStep(), unmatchedStep()));
        when(operationVersionRepository.findById(102L)).thenReturn(Optional.of(operationVersion()));

        manualOperations = new UseCaseManualOperations(canonicalRepository, operationVersionRepository,
                pipelineRunRepository, noticeService, new ObjectMapper());
    }

    @Test
    @DisplayName("Контекст паузы: target — сохранённый шаг, connectionOperation — архитектурная операция")
    void buildsThePauseContext() {
        JsonNode context = manualOperations.pauseContext(RUN_ID);

        assertThat(context.path("usecase").path("code").asText()).isEqualTo("UC-001");
        assertThat(context.path("usecase").path("biStepCode").asText()).isEqualTo("Step.00.00.02.08");
        assertThat(context.path("branch").asText()).isEqualTo("design");

        JsonNode mapped = context.path("mapped").get(0);
        assertThat(mapped.path("partId").asText()).isEqualTo("P-01");
        assertThat(mapped.path("target").path("stepVersionId").asLong()).isEqualTo(1L);
        assertThat(mapped.path("target").path("type").asText()).isEqualTo("POST");
        assertThat(mapped.path("target").path("name").asText()).isEqualTo("/orders");
        assertThat(mapped.path("target").path("productAlias").asText()).isEqualTo("BC-2");
        assertThat(mapped.path("connectionOperation").path("id").asInt()).isEqualTo(4242);
        assertThat(mapped.path("connectionOperation").path("operationName").asText()).isEqualTo("/orders");

        JsonNode unmapped = context.path("unmapped").get(0);
        assertThat(unmapped.path("partId").asText()).isEqualTo("P-02");
        assertThat(unmapped.path("target").path("stepVersionId").asLong()).isEqualTo(2L);
        assertThat(unmapped.path("connectionOperation").isEmpty()).isTrue();
        assertThat(unmapped.path("reason").asText()).contains("не найдена в архитектуре");
        assertThat(unmapped.path("suggestion").asText()).isEqualTo("map_existing | planned");
    }

    @Test
    @DisplayName("Шаг без сохранённой операции подсказывает только planned")
    void suggestsOnlyPlannedWithoutAnOperation() {
        when(canonicalRepository.findSteps(USECASE_VERSION_ID)).thenReturn(List.of(matchedStep(), stepWithoutOperation()));

        JsonNode context = manualOperations.pauseContext(RUN_ID);
        JsonNode part = context.path("unmapped").get(0);

        assertThat(part.path("partId").asText()).isEqualTo("P-02");
        assertThat(part.path("target").path("operationVersionId").isMissingNode()).isTrue();
        assertThat(part.path("suggestion").asText()).isEqualTo("planned");
    }

    @Test
    @DisplayName("Шаг с операцией подсказывает оба варианта")
    void suggestsBothWhenTheOperationIsSaved() {
        JsonNode part = manualOperations.pauseContext(RUN_ID).path("unmapped").get(0);

        assertThat(part.path("suggestion").asText()).isEqualTo("map_existing | planned");
    }

    @Test
    @DisplayName("Требуют решения — шаги без call_status")
    void listsUnmappedParts() {
        assertThat(manualOperations.unmappedParts(RUN_ID)).containsExactly("P-02");
    }

    @Test
    @DisplayName("Шаг с решением planned уходит из unmapped, connectionOperation остаётся пустым")
    void movesPlannedStepsOutOfUnmapped() {
        when(canonicalRepository.findSteps(USECASE_VERSION_ID)).thenReturn(List.of(matchedStep(), plannedStep()));

        JsonNode context = manualOperations.pauseContext(RUN_ID);

        assertThat(context.path("unmapped")).isEmpty();
        JsonNode planned = context.path("mapped").get(1);
        assertThat(planned.path("partId").asText()).isEqualTo("P-02");
        assertThat(planned.path("callStatus").asText()).isEqualTo("planned");
        assertThat(planned.path("connectionOperation").isEmpty()).isTrue();
        assertThat(manualOperations.unmappedParts(RUN_ID)).isEmpty();
    }

    @Test
    @DisplayName("Запуск без записанной версии UseCase — контекста паузы нет")
    void returnsNoContextBeforeThePhaseOneWrite() {
        when(canonicalRepository.findVersionByRunId(RUN_ID)).thenReturn(Optional.empty());

        assertThat(manualOperations.pauseContext(RUN_ID)).isNull();
        assertThat(manualOperations.unmappedParts(RUN_ID)).isEmpty();
    }

    @Test
    @DisplayName("map_existing проставляет connection_operation_id операции и architect_specified шагу")
    void appliesMapExisting() {
        manualOperations.applyDecision(RUN_ID, decision(ImportDecision.MAP_EXISTING,
                "{\"stepVersionId\":2,\"type\":\"GET\",\"name\":\"/status\"}",
                "{\"id\":555,\"operationType\":\"GET\",\"operationName\":\"/status\","
                        + "\"interfaceCode\":\"orders_api\",\"productAlias\":\"BC-2\"}"));

        ArgumentCaptor<OperationVersion> saved = ArgumentCaptor.forClass(OperationVersion.class);
        verify(operationVersionRepository).save(saved.capture());
        assertThat(saved.getValue().getConnectionOperationId()).isEqualTo(555);
        assertThat(saved.getValue().getJsonData()).contains("\"matched_operation\"")
                .contains("\"operationId\":555").contains("architect_decision");
        verify(canonicalRepository).updateStepDecision(eq(2L), eq("architect_specified"), anyString());
        assertThat(savedNotice().code()).isEqualTo("usecase.saver.decision.map_existing");
    }

    @Test
    @DisplayName("planned помечает шаг плановым и не трогает операцию")
    void appliesPlanned() {
        manualOperations.applyDecision(RUN_ID, decision(ImportDecision.PLANNED,
                "{\"stepVersionId\":2,\"type\":\"GET\",\"name\":\"/status\"}", null));

        verify(canonicalRepository).updateStepDecision(eq(2L), eq("planned"), anyString());
        verify(operationVersionRepository, never()).save(any());
        assertThat(savedNotice().code()).isEqualTo("usecase.saver.decision.planned");
    }

    @Test
    @DisplayName("Устаревший stepVersionId — 409, канон не меняется")
    void rejectsStaleContext() {
        assertThatThrownBy(() -> manualOperations.applyDecision(RUN_ID, decision(ImportDecision.MAP_EXISTING,
                "{\"stepVersionId\":999,\"type\":\"GET\",\"name\":\"/status\"}", "{\"id\":555}")))
                .isInstanceOf(PipelineRunConflictException.class)
                .hasMessageContaining("устарел");

        verify(canonicalRepository, never()).updateStepDecision(anyLong(), anyString(), anyString());
        assertThat(failureNotice().code()).isEqualTo("usecase.saver.decision.apply_failed");
    }

    @Test
    @DisplayName("Эхо запроса не совпало с сохранённым шагом — 409")
    void rejectsMismatchedEcho() {
        assertThatThrownBy(() -> manualOperations.applyDecision(RUN_ID, decision(ImportDecision.MAP_EXISTING,
                "{\"stepVersionId\":2,\"type\":\"POST\",\"name\":\"/status\"}", "{\"id\":555}")))
                .isInstanceOf(PipelineRunConflictException.class)
                .hasMessageContaining("устарел");
    }

    @Test
    @DisplayName("map_existing без connectionOperation.id — 400")
    void rejectsMapExistingWithoutConnection() {
        assertThatThrownBy(() -> manualOperations.applyDecision(RUN_ID, decision(ImportDecision.MAP_EXISTING,
                "{\"stepVersionId\":2,\"type\":\"GET\",\"name\":\"/status\"}", "{}")))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("connectionOperation.id");
    }

    @Test
    @DisplayName("Решение по части, которой нет в каноне — 404")
    void rejectsAnUnknownPart() {
        assertThatThrownBy(() -> manualOperations.applyDecision(RUN_ID,
                new ImportDecision(null, RUN_ID, "P-99", ImportDecision.MAP_EXISTING, "{\"stepVersionId\":2}", "{}")))
                .isInstanceOf(PipelineRunNotFoundException.class);
    }

    private ImportDecision decision(String type, String targetJson, String connectionJson) {
        return new ImportDecision(null, RUN_ID, "P-02", type, targetJson, connectionJson);
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

    private static OperationVersion operationVersion() {
        OperationVersion version = new OperationVersion();
        version.setId(102L);
        version.setName("/status");
        version.setJsonData("{\"type\":\"GET\"}");
        return version;
    }

    private static StepRow matchedStep() {
        return new StepRow(1L, "P-01", "POST /orders", 1, "main", "confirmed", "action",
                "{\"operation_code\":\"/orders\"}", 101L, "/orders", "POST", 4242,
                "{\"operationId\":4242,\"name\":\"/orders\",\"type\":\"POST\",\"interfaceCode\":\"orders_api\"}",
                "iface-1", "BC-2", "BC-2");
    }

    private static StepRow stepWithoutOperation() {
        return new StepRow(2L, "P-02", "уточняет у оператора", 2, "main", null, "action",
                "{\"reason\":\"Участник 'Ghost' не найден в CMDB — сторона вызова не определена\"}",
                null, null, null, null, null, null, null, null);
    }

    private static StepRow plannedStep() {
        return new StepRow(2L, "P-02", "GET /status", 2, "main", "planned", "action", "{}",
                102L, "/status", "GET", null, null, "iface-1", "BC-2", "BC-2");
    }

    private static StepRow unmatchedStep() {
        return new StepRow(2L, "P-02", "GET /status", 2, "main", null, "action",
                "{\"reason\":\"Операция GET /status не найдена в архитектуре продукта BC-2\"}",
                102L, "/status", "GET", null, null, "iface-1", "BC-2", "BC-2");
    }
}
