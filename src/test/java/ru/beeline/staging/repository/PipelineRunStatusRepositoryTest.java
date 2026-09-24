package ru.beeline.staging.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.beeline.staging.pipeline.manual.ManualOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineRunStatusRepositoryTest {

    private static final long RUN_ID = 1644367L;
    private static final String CONTEXT = """
            {"usecase":{"code":"UC-001"},"mapped":[{"partId":"P-01"}],"unmapped":[{"partId":"P-04"}]}""";

    private final ManualOperations manualOperations = mock(ManualOperations.class);
    private final PipelineRunStatusRepository repository =
            new PipelineRunStatusRepository(mock(JdbcTemplate.class), manualOperations);

    @ParameterizedTest
    @ValueSource(strings = {"awaiting_review", "reviewing", "completed"})
    @DisplayName("Контекст паузы из канона отдаётся как result для статусов паузы и завершения")
    void buildsResultForStatusesWithResult(String status) throws Exception {
        when(manualOperations.pauseContext("usecase", RUN_ID)).thenReturn(new ObjectMapper().readTree(CONTEXT));

        JsonNode result = repository.result(RUN_ID, "usecase", status);

        assertThat(result).isNotNull();
        assertThat(result.path("usecase").path("code").asText()).isEqualTo("UC-001");
        assertThat(result.path("mapped")).hasSize(1);
        assertThat(result.path("unmapped").get(0).path("partId").asText()).isEqualTo("P-04");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "transforming", "applying", "failed", "cancelled"})
    @DisplayName("Для остальных статусов result не отдаётся и канон не читается")
    void hidesResultForOtherStatuses(String status) {
        assertThat(repository.result(RUN_ID, "usecase", status)).isNull();
    }

    @Test
    @DisplayName("Тип без ручных операций — result null")
    void returnsNullForTypesWithoutManualOperations() {
        when(manualOperations.pauseContext("metric-queries", RUN_ID)).thenReturn(null);

        assertThat(repository.result(RUN_ID, "metric-queries", "completed")).isNull();
    }

    @Test
    @DisplayName("Сбой сборки контекста не ломает ответ статуса — result null")
    void returnsNullWhenTheContextCannotBeBuilt() {
        when(manualOperations.pauseContext("usecase", RUN_ID)).thenThrow(new IllegalStateException("нет версии"));

        assertThat(repository.result(RUN_ID, "usecase", "awaiting_review")).isNull();
    }
}
