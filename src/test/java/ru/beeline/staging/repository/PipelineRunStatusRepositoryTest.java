package ru.beeline.staging.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PipelineRunStatusRepositoryTest {

    private static final long RUN_ID = 1644367L;
    private static final String DRAFT = """
            {"usecase":{"code":"UC-001"},"mapped":[{"partId":"P-01"}],"unmapped":[{"partId":"P-04"}]}""";

    private final PipelineRunStatusRepository repository =
            new PipelineRunStatusRepository(mock(JdbcTemplate.class), new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"awaiting_review", "reviewing", "completed"})
    @DisplayName("draft_json отдаётся как result для статусов паузы и завершения")
    void parsesDraftForStatusesWithResult(String status) {
        JsonNode result = repository.result(RUN_ID, status, DRAFT);

        assertThat(result).isNotNull();
        assertThat(result.path("usecase").path("code").asText()).isEqualTo("UC-001");
        assertThat(result.path("mapped")).hasSize(1);
        assertThat(result.path("unmapped").get(0).path("partId").asText()).isEqualTo("P-04");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "transforming", "applying", "failed", "cancelled"})
    @DisplayName("Для остальных статусов result не отдаётся, даже если draft_json заполнен")
    void hidesDraftForOtherStatuses(String status) {
        assertThat(repository.result(RUN_ID, status, DRAFT)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("Пустой draft_json — result null")
    void returnsNullForEmptyDraft(String draftJson) {
        assertThat(repository.result(RUN_ID, "awaiting_review", draftJson)).isNull();
    }

    @Test
    @DisplayName("Некорректный draft_json не ломает ответ статуса — result null")
    void returnsNullForMalformedDraft() {
        assertThat(repository.result(RUN_ID, "awaiting_review", "{not json")).isNull();
    }
}
