package ru.beeline.staging.worker;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.pipeline.preadapter.ArtifactPreAdapter;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.SourceArtefactService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для {@link PreAdapterWorker.process}: передача name из metadata в recordSeen
 * (FR-003-16, критерий приёмки CR2; схема пайплайна: п.3 задачи e2e — d).
 *
 * Проверяем только извлечение имени из {@link ArtifactPreAdapter.FoundArtifact#metadata()} и
 * передачу его в {@link SourceArtefactService#recordSeen} — остальная логика воркера
 * (Camunda, stage log, run-ами) выносится в моки.
 */
class PreAdapterWorkerNameTest {

    private static final String MODULE_CODE = "fake-preadapter";

    private ConfigurationRepository configurationRepository;
    private ModuleResolver moduleResolver;
    private PipelineRunService pipelineRunService;
    private SourceArtefactService sourceArtefactService;
    private List<ArtifactPreAdapter> preAdapters;
    private PreAdapterWorker worker;

    /** Перехваченные вызовы recordSeen: (extUid, name). */
    private final List<Object[]> recordSeenCalls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        configurationRepository = mock(ConfigurationRepository.class);
        moduleResolver = mock(ModuleResolver.class);
        pipelineRunService = mock(PipelineRunService.class);
        sourceArtefactService = mock(SourceArtefactService.class);
        preAdapters = new ArrayList<>();
        worker = new PreAdapterWorker(configurationRepository, moduleResolver, pipelineRunService,
                sourceArtefactService, preAdapters);

        Configuration config = new Configuration();
        config.setId(10L);
        config.setDataTypeId(1L);
        config.setSourceSystemId(1L);
        config.setArtifactType("e2e-sequence");
        when(configurationRepository.findById(10L)).thenReturn(Optional.of(config));

        ArtifactPreAdapter adapter = mock(ArtifactPreAdapter.class);
        when(adapter.moduleCode()).thenReturn(MODULE_CODE);
        preAdapters.add(adapter);
        worker.init(); // строит registry из предъявленных преадаптеров

        when(moduleResolver.resolve("e2e-sequence", "pre-adapter")).thenReturn(MODULE_CODE);

        // createRun(artifactUid, artifactType, configId, batchId, scanRunId) -> PipelineRun{id}
        when(pipelineRunService.createRun(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            PipelineRun run = new PipelineRun();
            run.setId(900L);
            return run;
        });
        when(pipelineRunService.createRun(isNull(), any(), any(), any(), isNull())).thenAnswer(inv -> {
            PipelineRun run = new PipelineRun();
            run.setId(901L); // scan
            return run;
        });

        doAnswer(inv -> {
            String extUid = inv.getArgument(1);
            String name = inv.getArgument(4);
            recordSeenCalls.add(new Object[]{extUid, name});
            return null;
        }).when(sourceArtefactService).recordSeen(any(Configuration.class), anyString(), anyLong(), anyLong(), any());

        LockedExternalTask task = mock(LockedExternalTask.class);
        when(task.getVariables()).thenReturn(Map.of(
                "configurationId", 10,
                "artifactType", "e2e-sequence"));
        when(task.getProcessInstanceId()).thenReturn("pi-1");
        when(task.getVariable(anyString())).thenReturn(null);
        // сохраняем task для подстановки в process()
        this.task = task;
    }

    private LockedExternalTask task;

    private void scanReturns(ArtifactPreAdapter.FoundArtifact... artifacts) throws Exception {
        ArtifactPreAdapter adapter = preAdapters.get(0);
        when(adapter.scan(any(Configuration.class))).thenReturn(List.of(artifacts));
    }

    private String nameFor(String extUid) {
        return recordSeenCalls.stream()
                .filter(c -> extUid.equals(c[0]))
                .map(c -> (String) c[1])
                .findFirst().orElse("__NOT_CALLED__");
    }

    // ------------------------------------------------------------------
    // d1. metadata с ключом "name"
    // ------------------------------------------------------------------
    @Test
    @DisplayName("metadata с ключом name -> name передаётся в recordSeen")
    void metadataNameKeyPassedToRecordSeen() throws Exception {
        scanReturns(new ArtifactPreAdapter.FoundArtifact("scenario-1",
                Map.of("name", "Сценарий регистрации", "version", "1.0")));

        worker.process(task);

        assertThat(nameFor("scenario-1")).isEqualTo("Сценарий регистрации");
    }

    // ------------------------------------------------------------------
    // d2. metadata с ключом "productName"
    // ------------------------------------------------------------------
    @Test
    @DisplayName("metadata с productName (без name) -> productName передаётся в recordSeen")
    void metadataProductNameKeyPassedToRecordSeen() throws Exception {
        scanReturns(new ArtifactPreAdapter.FoundArtifact("product-a",
                Map.of("productName", "Супер-продукт", "productId", 42)));

        worker.process(task);

        assertThat(nameFor("product-a")).isEqualTo("Супер-продукт");
    }

    // ------------------------------------------------------------------
    // d3. приоритет: name > productName
    // ------------------------------------------------------------------
    @Test
    @DisplayName("metadata с (name и productName): в recordSeen уходит name (приоритет)")
    void metadataNameTakesPriorityOverProductName() throws Exception {
        scanReturns(new ArtifactPreAdapter.FoundArtifact("scenario-1",
                Map.of("name", "Имя из name", "productName", "Имя из productName")));

        worker.process(task);

        assertThat(nameFor("scenario-1")).isEqualTo("Имя из name");
    }

    // ------------------------------------------------------------------
    // d4. metadata null / без обоих ключей -> name null
    // ------------------------------------------------------------------
    @Test
    @DisplayName("metadata без name и productName -> в recordSeen уходит null (name не блокирует)")
    void metadataWithoutNameKeysPassesNull() throws Exception {
        scanReturns(new ArtifactPreAdapter.FoundArtifact("scenario-2",
                Map.of("version", "2.0", "notes", "no name here")));

        worker.process(task);

        assertThat(nameFor("scenario-2")).isNull();
    }

    @Test
    @DisplayName("metadata == null -> в recordSeen уходит null, пайплайн не падает")
    void nullMetadataPassesNull() throws Exception {
        scanReturns(new ArtifactPreAdapter.FoundArtifact("scenario-3", null));

        worker.process(task);

        assertThat(nameFor("scenario-3")).isNull();
    }

    // ------------------------------------------------------------------
    // d5. name с пробелами — не тримится до вызова recordSeen (защита в recordSeen)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("name с пробелами передаётся как есть (защита от blank — в recordSeen)")
    void nameWithWhitespacePassedRaw() throws Exception {
        scanReturns(new ArtifactPreAdapter.FoundArtifact("scenario-4",
                Map.of("name", "   ")));

        worker.process(task);

        assertThat(nameFor("scenario-4")).isEqualTo("   ");
    }
}