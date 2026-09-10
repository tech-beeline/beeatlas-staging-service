package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.client.DocumentServiceClient;
import ru.beeline.staging.domain.DataType;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.SourceSystem;
import ru.beeline.staging.dto.pipelinerun.CreatePipelineRunRequest;
import ru.beeline.staging.dto.pipelinerun.CreatePipelineRunResponse;
import ru.beeline.staging.exception.ActivePipelineRunException;
import ru.beeline.staging.exception.DocumentNotFoundException;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.repository.DataTypeRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.SourceSystemRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunImportServiceTest {

    private static final String E2E_PLANTUML = "e2e-plantuml";
    private static final String UID = "E2E-001";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PipelineRunRepository pipelineRunRepository;
    private DataTypeRepository dataTypeRepository;
    private SourceSystemRepository sourceSystemRepository;
    private DocumentServiceClient documentServiceClient;
    private PipelineExecutionService pipelineExecutionService;
    private PipelineRunImportService service;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        dataTypeRepository = mock(DataTypeRepository.class);
        sourceSystemRepository = mock(SourceSystemRepository.class);
        documentServiceClient = mock(DocumentServiceClient.class);
        pipelineExecutionService = mock(PipelineExecutionService.class);
        service = new PipelineRunImportService(pipelineRunRepository, dataTypeRepository, sourceSystemRepository,
                documentServiceClient, pipelineExecutionService);

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
    @DisplayName("Запуск по тексту PlantUML создаёт run в ветке main и стартует цепочку")
    void startsChainForPlantUmlPayload() {
        CreatePipelineRunResponse response = service.createImportRun(request("""
                {"name": "Смена тарифа", "plantUml": "@startuml\\nA -> B: GET /x\\n@enduml"}"""));

        assertThat(response.runId()).isEqualTo(77L);
        assertThat(response.artifactType()).isEqualTo(E2E_PLANTUML);
        assertThat(response.statusUrl()).isEqualTo("/api/v1/pipeline-runs/77/status");
        verify(pipelineExecutionService).submitArtifactChain(77L, E2E_PLANTUML, E2E_PLANTUML);

        PipelineRun saved = savedRun();
        assertThat(saved.getBranch()).isEqualTo("main");
        assertThat(saved.getSourceId()).isEqualTo(5);
        assertThat(saved.getPayload()).contains("Смена тарифа");
    }

    @Test
    @DisplayName("Явная ветка запуска фиксируется в pipeline_runs")
    void keepsExplicitBranch() {
        CreatePipelineRunRequest request = request("""
                {"name": "Смена тарифа", "plantUml": "@startuml\\n@enduml"}""");
        request.setBranch("feature/x");

        service.createImportRun(request);

        assertThat(savedRun().getBranch()).isEqualTo("feature/x");
    }

    @Test
    @DisplayName("plantUml и docId одновременно — 400")
    void rejectsBothPlantUmlAndDocId() {
        assertThatThrownBy(() -> service.createImportRun(request("""
                {"name": "N", "plantUml": "@startuml\\n@enduml", "docId": 12}""")))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("ровно одно");
    }

    @Test
    @DisplayName("Ни plantUml, ни docId — 400")
    void rejectsMissingSource() {
        assertThatThrownBy(() -> service.createImportRun(request("""
                {"name": "N"}""")))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("ровно одно");
    }

    @Test
    @DisplayName("Пустой name — 400")
    void rejectsMissingName() {
        assertThatThrownBy(() -> service.createImportRun(request("""
                {"name": "  ", "plantUml": "@startuml\\n@enduml"}""")))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("payload.name");
    }

    @Test
    @DisplayName("plantUml больше 512 КБ — 400")
    void rejectsOversizedPlantUml() {
        String oversized = "@".repeat(512 * 1024 + 1);
        assertThatThrownBy(() -> service.createImportRun(request(
                "{\"name\": \"N\", \"plantUml\": \"" + oversized + "\"}")))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("превышает");
    }

    @Test
    @DisplayName("Для e2e-plantuml допустим только source=manual")
    void rejectsNonManualSource() {
        CreatePipelineRunRequest request = request("""
                {"name": "N", "plantUml": "@startuml\\n@enduml"}""");
        request.setSource("confluence");

        assertThatThrownBy(() -> service.createImportRun(request))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("source=manual");
    }

    @Test
    @DisplayName("Ненайденный docId — 404")
    void rejectsUnknownDocId() {
        when(documentServiceClient.fetchContent(42L)).thenThrow(new DocumentNotFoundException(42L));

        assertThatThrownBy(() -> service.createImportRun(request("""
                {"name": "N", "docId": 42}""")))
                .isInstanceOf(PipelineRunNotFoundException.class)
                .hasMessageContaining("docId=42");
        verify(pipelineExecutionService, never()).submitArtifactChain(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("Незарегистрированный тип артефакта — 404")
    void rejectsUnknownArtifactType() {
        when(dataTypeRepository.findByCode(E2E_PLANTUML)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createImportRun(request("""
                {"name": "N", "plantUml": "@startuml\\n@enduml"}""")))
                .isInstanceOf(PipelineRunNotFoundException.class)
                .hasMessageContaining("data_types");
    }

    @Test
    @DisplayName("Активный запуск без supersedesRunId — 409 со ссылкой на активный")
    void rejectsSecondActiveRun() {
        PipelineRun active = new PipelineRun();
        active.setId(11L);
        when(pipelineRunRepository.findFirstByArtifactUidAndArtifactTypeAndStatusNotInOrderByStartedAtDesc(
                eq(UID), eq(E2E_PLANTUML), anyList())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.createImportRun(request("""
                {"name": "N", "plantUml": "@startuml\\n@enduml"}""")))
                .isInstanceOf(ActivePipelineRunException.class)
                .extracting(e -> ((ActivePipelineRunException) e).getActiveRunId())
                .isEqualTo(11L);
    }

    @Test
    @DisplayName("supersedesRunId отменяет активный запуск и пропускает новый")
    void cancelsSupersededRun() {
        PipelineRun previous = new PipelineRun();
        previous.setId(11L);
        previous.setStatus("awaiting_review");
        when(pipelineRunRepository.findById(11L)).thenReturn(Optional.of(previous));

        CreatePipelineRunRequest request = request("""
                {"name": "N", "plantUml": "@startuml\\n@enduml"}""");
        request.setSupersedesRunId(11L);

        service.createImportRun(request);

        verify(pipelineRunRepository).markCompleted(11L, "cancelled");
        verify(pipelineRunRepository, never())
                .findFirstByArtifactUidAndArtifactTypeAndStatusNotInOrderByStartedAtDesc(
                        anyString(), anyString(), anyList());
        assertThat(savedRun().getSupersedesRunId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("Ненайденный supersedesRunId — 404")
    void rejectsUnknownSupersededRun() {
        when(pipelineRunRepository.findById(99L)).thenReturn(Optional.empty());

        CreatePipelineRunRequest request = request("""
                {"name": "N", "plantUml": "@startuml\\n@enduml"}""");
        request.setSupersedesRunId(99L);

        assertThatThrownBy(() -> service.createImportRun(request))
                .isInstanceOf(PipelineRunNotFoundException.class)
                .hasMessageContaining("supersedesRunId=99");
    }

    @Test
    @DisplayName("Для usecase обязателен projectCode")
    void requiresProjectCodeForUseCase() {
        CreatePipelineRunRequest request = request("""
                {"name": "N", "plantUml": "@startuml\\n@enduml"}""");
        request.setArtifactType(PipelineRunImportService.USECASE_TYPE);

        assertThatThrownBy(() -> service.createImportRun(request))
                .isInstanceOf(PipelineRunBadRequestException.class)
                .hasMessageContaining("payload.projectCode");
    }

    @Test
    @DisplayName("statusUrl для usecase ждёт HITL-гейт")
    void buildsWaitingStatusUrlForUseCase() {
        CreatePipelineRunRequest request = request("""
                {"projectCode": "PRJ-1", "name": "N", "plantUml": "@startuml\\n@enduml"}""");
        request.setArtifactType(PipelineRunImportService.USECASE_TYPE);

        assertThat(service.createImportRun(request).statusUrl())
                .isEqualTo("/api/v1/pipeline-runs/77/status?waitFor=awaiting_review&timeoutMs=30000");
    }

    private CreatePipelineRunRequest request(String payloadJson) {
        CreatePipelineRunRequest request = new CreatePipelineRunRequest();
        request.setArtifactType(E2E_PLANTUML);
        request.setArtifactUid(UID);
        request.setSource("manual");
        try {
            request.setPayload(objectMapper.readTree(payloadJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    private PipelineRun savedRun() {
        org.mockito.ArgumentCaptor<PipelineRun> captor = org.mockito.ArgumentCaptor.forClass(PipelineRun.class);
        verify(pipelineRunRepository).save(captor.capture());
        return captor.getValue();
    }

    private DataType dataType() {
        DataType type = new DataType();
        type.setId(1);
        type.setCode(E2E_PLANTUML);
        return type;
    }

    private SourceSystem sourceSystem() {
        SourceSystem system = new SourceSystem();
        system.setId(5);
        system.setCode("beeatlas-ui");
        return system;
    }
}
