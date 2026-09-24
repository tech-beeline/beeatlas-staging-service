package ru.beeline.staging.pipeline.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.dto.notice.ValidateResult;
import ru.beeline.staging.pipeline.PipelineDefinitions;
import ru.beeline.staging.pipeline.saver.ArtifactSaver;
import ru.beeline.staging.pipeline.saver.E2ECanonicalSaver;
import ru.beeline.staging.pipeline.validator.ArtifactValidator;
import ru.beeline.staging.pipeline.validator.PlantUmlE2EValidator;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.repository.SourceSystemRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaverAndValidatorStageContractTest {

    private static final long RUN_ID = 77L;
    private static final long RAW_DATA_REF_ID = 42L;

    private final PipelineDefinitions pipelineDefinitions = new PipelineDefinitions();
    private final ModuleResolver moduleResolver = new ModuleResolver(pipelineDefinitions);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private PipelineRunService pipelineRunService;
    private PipelineRunRepository pipelineRunRepository;
    private RawDataRefRepository rawDataRefRepository;

    @BeforeEach
    void setUp() {
        pipelineRunService = mock(PipelineRunService.class);
        pipelineRunRepository = mock(PipelineRunRepository.class);
        rawDataRefRepository = mock(RawDataRefRepository.class);

        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setArtifactUid("E2E-001");
        run.setArtifactType("e2e-plantuml");
        run.setStatus("saving");
        run.setRawDataRefId(RAW_DATA_REF_ID);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        RawDataRef ref = new RawDataRef();
        ref.setId(RAW_DATA_REF_ID);
        ref.setRawContent("@startuml\n@enduml".getBytes(StandardCharsets.UTF_8));
        ref.setCanonicalSnapshotJson("{}");
        when(rawDataRefRepository.findById(RAW_DATA_REF_ID)).thenReturn(Optional.of(ref));
        when(pipelineRunService.startStage(anyLong(), anyString(), any())).thenReturn(5L);
    }

    @Test
    @DisplayName("Saver больше не завершает прогон — это делает publisher")
    void saverDoesNotCompleteTheRunAnymore() throws Exception {
        ArtifactSaver saver = mock(ArtifactSaver.class);
        when(saver.moduleCode()).thenReturn(E2ECanonicalSaver.MODULE_CODE);
        when(saver.save(anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(SaveResult.of(Map.of("batchId", 3L)));

        SaverStage stage = new SaverStage(List.of(saver), moduleResolver, pipelineRunService,
                pipelineRunRepository, rawDataRefRepository);
        stage.init();
        stage.execute(RUN_ID);

        verify(saver).save(eq("E2E-001"), eq("e2e-plantuml"), eq(RAW_DATA_REF_ID), eq(RUN_ID), any());
        verify(pipelineRunService, never()).completeRun(anyLong());
    }

    @Test
    @DisplayName("Причина падения валидатора перечисляет сами ошибки, а не их количество")
    void validatorFailureListsTheReasons() throws Exception {
        ArtifactValidator validator = mock(ArtifactValidator.class);
        when(validator.moduleCode()).thenReturn(PlantUmlE2EValidator.MODULE_CODE);
        when(validator.validate(anyString(), anyString(), any())).thenReturn(ValidateResult.empty());
        when(pipelineRunService.saveNotices(anyLong(), any())).thenReturn(List.of(
                errorNotice("Участник 'User' не найден в CMDB", 10),
                errorNotice("Ошибка синтаксиса PlantUML", 3)));

        ValidatorStage stage = new ValidatorStage(List.of(validator), rawDataRefRepository, pipelineRunRepository,
                moduleResolver, pipelineRunService, mock(SourceSystemRepository.class), objectMapper);
        stage.init();

        assertThatThrownBy(() -> stage.execute(RUN_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Участник 'User' не найден в CMDB (строка 10)")
                .hasMessageContaining("Ошибка синтаксиса PlantUML (строка 3)");

        verify(pipelineRunService).failStage(eq(5L), eq(RUN_ID), eq("validator"),
                contains("Участник 'User' не найден в CMDB"));
    }

    private ArtifactNotice errorNotice(String reason, int line) {
        String details = "{\"reason\":\"" + reason + "\",\"line\":" + line + "}";
        return new ArtifactNotice(1L, null, "e2e_plantuml.validation.participants.unrecognized", "error",
                "validation", RAW_DATA_REF_ID, null, null, null, "code", details, null, null, null, null);
    }
}
