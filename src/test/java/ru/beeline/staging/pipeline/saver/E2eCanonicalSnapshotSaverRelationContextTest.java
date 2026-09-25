package ru.beeline.staging.pipeline.saver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.canonical.E2eScenarioVersion;
import ru.beeline.staging.domain.canonical.OperationRelationVersion;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.pipeline.transformer.E2ESequenceSnapshot;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.SourceSystemRepository;
import ru.beeline.staging.repository.canonical.OperationRelationVersionRepository;
import ru.beeline.staging.repository.canonical.ProductRepository;
import ru.beeline.staging.repository.canonical.ProductVersionRepository;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.service.RawDataContextService;
import ru.beeline.staging.service.RunBranchResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Связь без raw_data_context не находится выборкой актуального состояния (cte_op_rel джойнит
 * operation_relation_versions через raw_data_contexts), поэтому дерево вызовов молча уезжало в
 * fdm-products пустым для потока plantuml, где decomposer не проставляет JSON-указатель.
 */
class E2eCanonicalSnapshotSaverRelationContextTest {

    private static final long REF_ID = 1345497L;
    private static final long RUN_ID = 1739174L;
    private static final long CONTEXT_ID = 555L;
    private static final String UID = "auto_test_plantuml_src_92302";

    private OperationRelationVersionRepository operationRelationVersionRepository;
    private OperationMatchService operationMatchService;
    private E2eScenarioMatchService e2eScenarioMatchService;
    private PipelineRunService pipelineRunService;
    private RunBranchResolver runBranchResolver;
    private RawDataContextService rawDataContextService;
    private E2eCanonicalSnapshotSaver saver;

    @BeforeEach
    void setUp() {
        operationRelationVersionRepository = mock(OperationRelationVersionRepository.class);
        operationMatchService = mock(OperationMatchService.class);
        e2eScenarioMatchService = mock(E2eScenarioMatchService.class);
        pipelineRunService = mock(PipelineRunService.class);
        runBranchResolver = mock(RunBranchResolver.class);
        rawDataContextService = mock(RawDataContextService.class);

        saver = new E2eCanonicalSnapshotSaver(
                operationRelationVersionRepository,
                mock(ProductRepository.class),
                mock(ProductVersionRepository.class),
                mock(BiStepMatchService.class),
                e2eScenarioMatchService,
                mock(ProductMatchService.class),
                mock(ContainerMatchService.class),
                mock(InterfaceMatchService.class),
                operationMatchService,
                pipelineRunService,
                mock(PipelineRunRepository.class),
                runBranchResolver,
                mock(ConfigurationRepository.class),
                mock(SourceSystemRepository.class),
                rawDataContextService);

        ArtifactBatch batch = new ArtifactBatch();
        batch.setId(4544L);
        when(pipelineRunService.createBatch(anyString(), anyString(), anyLong(), anyLong(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(batch);
        when(runBranchResolver.resolve(anyLong())).thenReturn("main");

        OperationVersion operationVersion = new OperationVersion();
        operationVersion.setId(11L);
        when(operationMatchService.matchOrCreate(anyString(), anyString(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(operationVersion);
        when(e2eScenarioMatchService.matchOrCreate(anyString(), anyString(), any(), any(),
                any(), any(), anyLong(), anyLong(), anyString()))
                .thenReturn(mock(E2eScenarioVersion.class));
        when(rawDataContextService.pointToFreeText(anyLong(), any())).thenReturn(CONTEXT_ID);
        when(rawDataContextService.pointTo(anyLong(), anyString())).thenReturn(CONTEXT_ID);
    }

    private E2ESequenceSnapshot snapshotWithRelationContext(String context) {
        E2ESequenceSnapshot snapshot = new E2ESequenceSnapshot();

        E2ESequenceSnapshot.E2eScenarioDraft scenario = new E2ESequenceSnapshot.E2eScenarioDraft();
        scenario.setUid(UID);
        scenario.setExtUid(UID);
        scenario.setName("QA source probe");
        snapshot.setE2eScenario(scenario);

        E2ESequenceSnapshot.OperationDraft operation = new E2ESequenceSnapshot.OperationDraft();
        operation.setExtUid("op-1");
        snapshot.getOperations().add(operation);

        E2ESequenceSnapshot.OperationRelationDraft relation = new E2ESequenceSnapshot.OperationRelationDraft();
        relation.setCalleeOperationExtUid("op-1");
        relation.setCallOrder(0);
        relation.setContext(context);
        snapshot.getOperationRelations().add(relation);

        return snapshot;
    }

    @Test
    @DisplayName("Корневая связь plantuml без указателя всё равно получает контекст (free_text)")
    void assignsFreeTextContextWhenDecomposerGaveNone() {
        saver.saveSnapshot(snapshotWithRelationContext(null), REF_ID, RUN_ID, UID, "e2e-plantuml");

        ArgumentCaptor<OperationRelationVersion> saved = ArgumentCaptor.forClass(OperationRelationVersion.class);
        verify(operationRelationVersionRepository).save(saved.capture());
        assertThat(saved.getValue().getRawDataContextId()).isEqualTo(CONTEXT_ID);
        verify(rawDataContextService).pointToFreeText(eq(REF_ID), isNull());
        verify(rawDataContextService, never()).pointTo(anyLong(), anyString());
    }

    @Test
    @DisplayName("Поток sparx с JSON-указателем по-прежнему резолвится через pointTo")
    void keepsJsonPointerResolutionForSparxFlow() {
        saver.saveSnapshot(snapshotWithRelationContext("/calls/0"), REF_ID, RUN_ID, UID, "e2e-sequence");

        ArgumentCaptor<OperationRelationVersion> saved = ArgumentCaptor.forClass(OperationRelationVersion.class);
        verify(operationRelationVersionRepository).save(saved.capture());
        assertThat(saved.getValue().getRawDataContextId()).isEqualTo(CONTEXT_ID);
        verify(rawDataContextService).pointTo(REF_ID, "/calls/0");
        verify(rawDataContextService, never()).pointToFreeText(anyLong(), any());
    }
}
