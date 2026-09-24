package ru.beeline.staging.pipeline.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.dto.e2e.E2ePlantUmlPauseContext;
import ru.beeline.staging.dto.notice.TransformResult;
import ru.beeline.staging.pipeline.transformer.ArtifactTransformer;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.repository.SourceSystemRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransformerStageTest {

    private static final long RUN_ID = 2714046L;
    private static final long RAW_DATA_REF_ID = 926808L;

    private PipelineRunRepository pipelineRunRepository;
    private RawDataRefRepository rawDataRefRepository;
    private PipelineRunService pipelineRunService;
    private ArtifactTransformer transformer;
    private TransformerStage stage;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        rawDataRefRepository = mock(RawDataRefRepository.class);
        pipelineRunService = mock(PipelineRunService.class);
        transformer = mock(ArtifactTransformer.class);
        ModuleResolver moduleResolver = mock(ModuleResolver.class);

        when(transformer.moduleCode()).thenReturn("e2e-plantuml-transformer");
        when(moduleResolver.resolve("e2e-plantuml", "transformer")).thenReturn("e2e-plantuml-transformer");
        when(pipelineRunService.startStage(anyLong(), anyString(), any())).thenReturn(11L);

        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setArtifactUid("{34bf8865}");
        run.setArtifactType("e2e-plantuml");
        run.setRawDataRefId(RAW_DATA_REF_ID);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        RawDataRef ref = new RawDataRef();
        ref.setId(RAW_DATA_REF_ID);
        ref.setRawContent("@startuml\n@enduml".getBytes(StandardCharsets.UTF_8));
        when(rawDataRefRepository.findById(RAW_DATA_REF_ID)).thenReturn(Optional.of(ref));

        stage = new TransformerStage(List.of(transformer), rawDataRefRepository, pipelineRunRepository,
                new ObjectMapper(), moduleResolver, pipelineRunService, mock(SourceSystemRepository.class));
        stage.init();
    }

    @Test
    @DisplayName("Повторный импорт того же текста: канон не переписывается, но контекст паузы собирается")
    void collectsThePauseContextEvenWhenTheContentIsUnchanged() throws Exception {
        when(pipelineRunService.isAlreadyFullyProcessed(anyString(), anyString(), anyLong())).thenReturn(true);
        when(transformer.transform(anyString(), anyString(), any()))
                .thenReturn(TransformResult.of(null, List.of(), pauseContext()));

        stage.execute(RUN_ID);

        verify(pipelineRunRepository).saveDraftJson(eq(RUN_ID), contains("E2E-001"));
        verify(rawDataRefRepository, never()).save(any());
        verify(pipelineRunService, never()).saveNotices(anyLong(), anyList());
        verify(pipelineRunService).completeStage(11L, "skipped: content unchanged", null);
    }

    @Test
    @DisplayName("Сбой сборки контекста при пропуске не роняет стадию")
    void survivesAFailingTransformWhileSkipping() throws Exception {
        when(pipelineRunService.isAlreadyFullyProcessed(anyString(), anyString(), anyLong())).thenReturn(true);
        when(transformer.transform(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("fdm-products недоступен"));

        stage.execute(RUN_ID);

        verify(pipelineRunRepository, never()).saveDraftJson(anyLong(), anyString());
        verify(pipelineRunService).completeStage(11L, "skipped: content unchanged", null);
    }

    @Test
    @DisplayName("Обычный прогон пишет и снимок, и контекст паузы")
    void writesSnapshotAndPauseContextOnAnormalRun() throws Exception {
        when(pipelineRunService.isAlreadyFullyProcessed(anyString(), anyString(), anyLong())).thenReturn(false);
        when(transformer.transform(anyString(), anyString(), any()))
                .thenReturn(TransformResult.of("{}", List.of(), pauseContext()));
        when(pipelineRunService.saveNotices(anyLong(), anyList())).thenReturn(List.of());

        stage.execute(RUN_ID);

        verify(rawDataRefRepository).save(any());
        verify(pipelineRunRepository).saveDraftJson(eq(RUN_ID), contains("E2E-001"));
    }

    private E2ePlantUmlPauseContext pauseContext() {
        return new E2ePlantUmlPauseContext(
                new E2ePlantUmlPauseContext.E2e("E2E-001", "Оплата", null), List.of(), List.of());
    }
}
