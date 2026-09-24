package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.client.DocumentServiceClient;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.DataType;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.SourceSystem;
import ru.beeline.staging.dto.pipelinerun.CreatePipelineRunRequest;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.DataTypeRepository;
import ru.beeline.staging.repository.ArtifactBatchRepository;
import ru.beeline.staging.repository.PipelineDefinitionEntryRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.PipelineStageLogRepository;
import ru.beeline.staging.repository.SourceSystemRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunAuthorTest {

    private static final String E2E_PLANTUML = "e2e-plantuml";
    private static final String UID = "E2E-001";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PipelineRunRepository pipelineRunRepository;
    private PipelineRunImportService importService;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        DataTypeRepository dataTypeRepository = mock(DataTypeRepository.class);
        SourceSystemRepository sourceSystemRepository = mock(SourceSystemRepository.class);
        ConfigurationRepository configurationRepository = mock(ConfigurationRepository.class);
        DocumentServiceClient documentServiceClient = mock(DocumentServiceClient.class);
        PipelineExecutionService pipelineExecutionService = mock(PipelineExecutionService.class);
        importService = new PipelineRunImportService(pipelineRunRepository, dataTypeRepository,
                sourceSystemRepository, configurationRepository, documentServiceClient, pipelineExecutionService);

        when(configurationRepository.findByArtifactTypeAndIsActiveTrue(anyString()))
                .thenReturn(List.of(manualConfiguration()));
        when(dataTypeRepository.findByCode(anyString())).thenReturn(Optional.of(dataType()));
        when(sourceSystemRepository.findByCode(anyString())).thenReturn(Optional.of(sourceSystem()));
        when(pipelineRunRepository.findFirstByArtifactUidAndArtifactTypeAndStatusNotInOrderByStartedAtDesc(
                anyString(), anyString(), anyList())).thenReturn(Optional.empty());
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(invocation -> {
            PipelineRun run = invocation.getArgument(0);
            run.setId(77L);
            return run;
        });
    }

    @Test
    @DisplayName("Переданный USER-ID сохраняется автором запуска")
    void storesTheCallerAsTheAuthorOfTheRun() {
        importService.createImportRun(plantUmlRequest(), 434318);

        assertThat(savedRun().getCreatedByUserId()).isEqualTo(434318);
    }

    @Test
    @DisplayName("Без USER-ID запуск создаётся без автора")
    void createsTheRunWithoutAnAuthorWhenTheHeaderIsAbsent() {
        importService.createImportRun(plantUmlRequest(), null);

        assertThat(savedRun().getCreatedByUserId()).isNull();
    }

    @Test
    @DisplayName("Перезапуск сохраняет автора исходного запуска")
    void keepsTheOriginalAuthorWhenARunIsSuperseded() {
        PipelineRun superseded = new PipelineRun();
        superseded.setId(12L);
        superseded.setStatus("completed");
        superseded.setCreatedByUserId(999);
        when(pipelineRunRepository.findById(12L)).thenReturn(Optional.of(superseded));

        CreatePipelineRunRequest request = plantUmlRequest();
        request.setSupersedesRunId(12L);
        importService.createImportRun(request, null);

        assertThat(savedRun().getCreatedByUserId()).isEqualTo(999);
    }

    @Test
    @DisplayName("Дочерний запуск наследует автора прогона-скана")
    void childRunInheritsTheAuthorOfItsScan() {
        PipelineRun scan = new PipelineRun();
        scan.setId(500L);
        scan.setCreatedByUserId(434318);
        when(pipelineRunRepository.findById(500L)).thenReturn(Optional.of(scan));

        pipelineRunService().createRun(UID, E2E_PLANTUML, 1L, "batch-1", 500L);

        assertThat(savedRun().getCreatedByUserId()).isEqualTo(434318);
    }

    @Test
    @DisplayName("Запуск без прогона-скана остаётся без автора")
    void runWithoutAScanHasNoAuthor() {
        pipelineRunService().createRun(UID, E2E_PLANTUML, 1L, "batch-1", null);

        assertThat(savedRun().getCreatedByUserId()).isNull();
    }

    private PipelineRunService pipelineRunService() {
        PipelineDefinitionEntryRepository definitionRepository = mock(PipelineDefinitionEntryRepository.class);
        when(definitionRepository.findByArtifactTypeAndCurrentTrue(anyString())).thenReturn(Optional.empty());
        return new PipelineRunService(pipelineRunRepository, mock(PipelineStageLogRepository.class),
                mock(ArtifactBatchRepository.class), definitionRepository, mock(ArtifactNoticeService.class),
                new SimpleMeterRegistry());
    }

    private PipelineRun savedRun() {
        ArgumentCaptor<PipelineRun> captor = ArgumentCaptor.forClass(PipelineRun.class);
        verify(pipelineRunRepository).save(captor.capture());
        return captor.getValue();
    }

    private CreatePipelineRunRequest plantUmlRequest() {
        CreatePipelineRunRequest request = new CreatePipelineRunRequest();
        request.setArtifactType(E2E_PLANTUML);
        request.setArtifactUid(UID);
        request.setSource("manual");
        try {
            request.setPayload(objectMapper.readTree("""
                    {"name": "Сценарий", "plantUml": "@startuml\\n@enduml"}
                    """));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    private static Configuration manualConfiguration() {
        Configuration configuration = new Configuration();
        configuration.setId(1L);
        return configuration;
    }

    private static DataType dataType() {
        DataType type = new DataType();
        type.setCode(E2E_PLANTUML);
        return type;
    }

    private static SourceSystem sourceSystem() {
        SourceSystem system = new SourceSystem();
        system.setId(3);
        system.setCode("beeatlas-ui");
        return system;
    }
}
