package ru.beeline.staging.pipeline.manual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class E2ePlantUmlManualOperationsTest {

    private static final long RUN_ID = 2687595L;
    private static final String CONTEXT = """
            {"e2e":{"uid":"E2E-001","name":"Оплата заказа","biStepCode":"Step.00.00.02.08"},
             "participants":[{"alias":"orders","resolved":true,"productAlias":"orders","kind":"system"}],
             "requests":[{"order":0,"fromAlias":"web","toAlias":"orders","label":"GET /api/v1/orders",
                          "type":"GET","path":"/api/v1/orders","unknown":false,
                          "match":{"status":"matched","connectionOperationId":18421}}]}""";

    private PipelineRunRepository pipelineRunRepository;
    private E2ePlantUmlManualOperations manualOperations;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        manualOperations = new E2ePlantUmlManualOperations(pipelineRunRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("Контекст паузы отдаётся из draft_json прогона")
    void readsThePauseContextFromTheRun() {
        givenRun(CONTEXT);

        JsonNode context = manualOperations.pauseContext(RUN_ID);

        assertThat(context.path("e2e").path("uid").asText()).isEqualTo("E2E-001");
        assertThat(context.path("participants").get(0).path("alias").asText()).isEqualTo("orders");
        assertThat(context.path("requests").get(0).path("fromAlias").asText()).isEqualTo("web");
        assertThat(context.path("requests").get(0).path("match").path("connectionOperationId").asInt())
                .isEqualTo(18421);
    }

    @Test
    @DisplayName("Пустой draft_json — контекста нет, но пауза всё равно обязательна")
    void keepsThePauseWithoutAContext() {
        givenRun(null);

        assertThat(manualOperations.pauseContext(RUN_ID)).isNull();
        assertThat(manualOperations.pauseRequired(RUN_ID)).isTrue();
    }

    @Test
    @DisplayName("Некорректный JSON не ломает статус — контекста нет")
    void survivesMalformedJson() {
        givenRun("{not json");

        assertThat(manualOperations.pauseContext(RUN_ID)).isNull();
    }

    @Test
    @DisplayName("Решений у типа нет — apply и decline")
    void rejectsDecisions() {
        assertThat(manualOperations.unmappedParts(RUN_ID)).isEmpty();
        assertThatThrownBy(() -> manualOperations.applyDecision(RUN_ID,
                new ImportDecision(null, RUN_ID, "P-01", ImportDecision.MAP_EXISTING, "{}", "{}")))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("decline");
    }

    private void givenRun(String draftJson) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setArtifactType("e2e-plantuml");
        run.setDraftJson(draftJson);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
