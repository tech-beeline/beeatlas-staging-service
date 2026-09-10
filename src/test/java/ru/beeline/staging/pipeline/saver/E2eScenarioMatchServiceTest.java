package ru.beeline.staging.pipeline.saver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.domain.canonical.E2eScenario;
import ru.beeline.staging.domain.canonical.E2eScenarioVersion;
import ru.beeline.staging.repository.canonical.E2eScenarioRepository;
import ru.beeline.staging.repository.canonical.E2eScenarioVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class E2eScenarioMatchServiceTest {

    private E2eScenarioVersionRepository versionRepository;
    private E2eScenarioMatchService service;

    @BeforeEach
    void setUp() {
        E2eScenarioRepository scenarioRepository = mock(E2eScenarioRepository.class);
        versionRepository = mock(E2eScenarioVersionRepository.class);
        ArtifactNoticeService noticeService = mock(ArtifactNoticeService.class);
        service = new E2eScenarioMatchService(scenarioRepository, versionRepository, noticeService);

        E2eScenario scenario = new E2eScenario();
        scenario.setId(1L);
        scenario.setUid("E2E-001");
        when(scenarioRepository.findByUid("E2E-001")).thenReturn(Optional.of(scenario));
        when(noticeService.saveNotices(anyLong(), anyList())).thenReturn(List.of());
        when(versionRepository.save(any(E2eScenarioVersion.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("Ветка запуска попадает в e2e_scenario_versions.branch_name")
    void writesBranchName() {
        service.matchOrCreate("E2E-001", "EXT-1", "Смена тарифа", null, null, null, 42L, 100L, "feature/x");

        ArgumentCaptor<E2eScenarioVersion> captor = ArgumentCaptor.forClass(E2eScenarioVersion.class);
        verify(versionRepository).save(captor.capture());
        assertThat(captor.getValue().getBranchName()).isEqualTo("feature/x");
    }
}
