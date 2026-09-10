package ru.beeline.staging.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunBranchResolverTest {

    private PipelineRunRepository pipelineRunRepository;
    private RunBranchResolver resolver;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        resolver = new RunBranchResolver(pipelineRunRepository);
    }

    @Test
    @DisplayName("Ветка запуска берётся из pipeline_runs.branch")
    void takesBranchFromRun() {
        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(run("feature/x")));

        assertThat(resolver.resolve(7L)).isEqualTo("feature/x");
    }

    @Test
    @DisplayName("Прогон без ветки — main")
    void fallsBackToMainForRunWithoutBranch() {
        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(run(null)));

        assertThat(resolver.resolve(7L)).isEqualTo("main");
    }

    @Test
    @DisplayName("Прогона нет — main")
    void fallsBackToMainForUnknownRun() {
        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(7L)).isEqualTo("main");
        assertThat(resolver.resolve(null)).isEqualTo("main");
    }

    private PipelineRun run(String branch) {
        PipelineRun run = new PipelineRun();
        run.setId(7L);
        run.setBranch(branch);
        return run;
    }
}
