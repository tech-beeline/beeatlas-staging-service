package ru.beeline.staging.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.beeline.staging.dto.rundetails.PipelineRunDetails;
import ru.beeline.staging.repository.PipelineRunDetailsRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserPipelineRunsEndpointTest {

    private static final String USER_ID_HEADER = "user-id";

    private PipelineRunDetailsRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(PipelineRunDetailsRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserPipelineRunsController(repository)).build();
    }

    @Test
    @DisplayName("Без заголовка USER-ID запрос отклоняется")
    void rejectsARequestWithoutTheUserHeader() throws Exception {
        mockMvc.perform(get("/api/v1/user/pipeline-runs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Запрос не содержит данных пользователя"));

        verify(repository, never()).findByCreatedByUserId(anyInt(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Пустой заголовок USER-ID равнозначен отсутствию")
    void rejectsABlankUserHeader() throws Exception {
        mockMvc.perform(get("/api/v1/user/pipeline-runs").header(USER_ID_HEADER, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Запрос не содержит данных пользователя"));
    }

    @Test
    @DisplayName("Нечисловой USER-ID отклоняется отдельным сообщением")
    void rejectsANonIntegerUserHeader() throws Exception {
        mockMvc.perform(get("/api/v1/user/pipeline-runs").header(USER_ID_HEADER, "KYuSamsonov"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Запрос содержит не верный формат USER-ID"));
    }

    @Test
    @DisplayName("Пустое значение artifact-type отклоняется")
    void rejectsABlankArtifactType() throws Exception {
        mockMvc.perform(get("/api/v1/user/pipeline-runs")
                        .header(USER_ID_HEADER, "434318")
                        .param("artifact-type", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Пустой значение параметра artifact-type"));
    }

    @Test
    @DisplayName("Без artifact-type отдаются все прогоны пользователя")
    void returnsEveryRunOfTheUserWhenNoTypeIsGiven() throws Exception {
        when(repository.findByCreatedByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(details(2656311L, 2656224L, "metric-queries")));

        mockMvc.perform(get("/api/v1/user/pipeline-runs").header(USER_ID_HEADER, "434318"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value(2656311))
                .andExpect(jsonPath("$[0].scanRunId").value(2656224))
                .andExpect(jsonPath("$[0].artifactType").value("metric-queries"))
                .andExpect(jsonPath("$[0].status").value("completed"))
                .andExpect(jsonPath("$[0].stages").isArray());

        ArgumentCaptor<Integer> userId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> artifactType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        verify(repository).findByCreatedByUserId(userId.capture(), artifactType.capture(),
                limit.capture(), offset.capture());
        assertThat(userId.getValue()).isEqualTo(434318);
        assertThat(artifactType.getValue()).isNull();
        assertThat(limit.getValue()).isEqualTo(50);
        assertThat(offset.getValue()).isZero();
    }

    @Test
    @DisplayName("Фильтр по artifact-type передаётся в запрос как есть")
    void passesTheArtifactTypeFilterDownUntouched() throws Exception {
        when(repository.findByCreatedByUserId(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/user/pipeline-runs")
                        .header(USER_ID_HEADER, "434318")
                        .param("artifact-type", " E2E-PlantUML "))
                .andExpect(status().isOk());

        ArgumentCaptor<String> artifactType = ArgumentCaptor.forClass(String.class);
        verify(repository).findByCreatedByUserId(any(), artifactType.capture(), anyInt(), anyInt());
        assertThat(artifactType.getValue()).isEqualTo("E2E-PlantUML");
    }

    @Test
    @DisplayName("Отсутствие подходящих прогонов — это пустой массив и 200")
    void returnsAnEmptyArrayWhenTheUserHasNoRuns() throws Exception {
        when(repository.findByCreatedByUserId(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/user/pipeline-runs").header(USER_ID_HEADER, "434318"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("Свои limit и offset доходят до запроса")
    void passesPagingParametersDown() throws Exception {
        when(repository.findByCreatedByUserId(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/user/pipeline-runs")
                        .header(USER_ID_HEADER, "434318")
                        .param("limit", "10")
                        .param("offset", "20"))
                .andExpect(status().isOk());

        verify(repository).findByCreatedByUserId(434318, null, 10, 20);
    }

    @Test
    @DisplayName("Отрицательные limit и offset отклоняются")
    void rejectsNegativePagingParameters() throws Exception {
        mockMvc.perform(get("/api/v1/user/pipeline-runs")
                        .header(USER_ID_HEADER, "434318")
                        .param("limit", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Параметры limit и offset не могут быть отрицательными"));

        mockMvc.perform(get("/api/v1/user/pipeline-runs")
                        .header(USER_ID_HEADER, "434318")
                        .param("offset", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Параметры limit и offset не могут быть отрицательными"));

        verify(repository, never()).findByCreatedByUserId(anyInt(), anyString(), anyInt(), anyInt());
    }

    private static PipelineRunDetails details(Long runId, Long scanRunId, String artifactType) {
        return new PipelineRunDetails(runId, scanRunId, "РБС", "РБС", artifactType, "completed", "Sparx EA",
                LocalDateTime.of(2026, 9, 22, 9, 36, 33), LocalDateTime.of(2026, 9, 22, 9, 39, 8),
                LocalDateTime.of(2026, 9, 22, 9, 39, 8), 326259L, 3036L, List.of());
    }
}
