package ru.beeline.staging.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.repository.ArtifactBatchRepository;
import ru.beeline.staging.repository.PipelineDefinitionEntryRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.PipelineStageLogRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Что скан делает с артефактом, у которого уже есть прогон (дефекты QA-1 и QA-4).
 *
 * Ключевой инвариант: в child_run_ids скана попадают ровно те прогоны, которыми этот скан
 * владеет (parent_run_id указывает на него) — иначе один прогон числится детьми сотен сканов,
 * а упавший с исчерпанными ретраями переиспользуется бесконечно и артефакт перестаёт
 * обрабатываться вообще.
 */
class PipelineRunServiceFanOutTest {

    private static final String TYPE = "e2e-sequence";
    private static final long   SCAN_ID = 500L;

    private PipelineRunRepository runRepository;
    private PipelineRunService    service;

    private final AtomicLong idSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        runRepository = mock(PipelineRunRepository.class);
        PipelineStageLogRepository stageLogRepository = mock(PipelineStageLogRepository.class);
        ArtifactBatchRepository batchRepository = mock(ArtifactBatchRepository.class);
        PipelineDefinitionEntryRepository definitionRepository = mock(PipelineDefinitionEntryRepository.class);
        ArtifactNoticeService noticeService = mock(ArtifactNoticeService.class);

        when(stageLogRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(definitionRepository.findByArtifactTypeAndCurrentTrue(anyString())).thenReturn(Optional.empty());
        when(runRepository.save(any(PipelineRun.class))).thenAnswer(invocation -> {
            PipelineRun run = invocation.getArgument(0);
            if (run.getId() == null) run.setId(idSeq.incrementAndGet());
            return run;
        });
        when(runRepository.findById(anyLong())).thenReturn(Optional.empty());

        service = new PipelineRunService(runRepository, stageLogRepository, batchRepository,
                definitionRepository, noticeService, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "maxAutoRetries", 3);
    }

    @Test
    @DisplayName("Нет прогона по артефакту — скан создаёт свой и забирает его в дети")
    void createsRunWhenNoneQueued() {
        existingRunFor("uid-1", null);

        List<PipelineRunService.ChildOutcome> outcomes = fanOut("uid-1");

        assertThat(outcomes).singleElement()
                .extracting(PipelineRunService.ChildOutcome::disposition)
                .isEqualTo(PipelineRunService.Disposition.CREATED);
        assertThat(outcomes.get(0).run().getParentRunId()).isEqualTo(SCAN_ID);
        assertThat(outcomes.get(0).ownedByThisScan()).isTrue();
    }

    @Test
    @DisplayName("QA-1: упавший с исчерпанными ретраями не переиспользуется — BLOCKED, ничего не диспатчится")
    void doesNotReuseRunWithExhaustedRetries() {
        PipelineRun zombie = existingRunFor("uid-1", run -> {
            run.setId(42L);
            run.setStatus("failed");
            run.setRetryCount(3);
            run.setParentRunId(100L);
        });

        List<PipelineRunService.ChildOutcome> outcomes = fanOut("uid-1");

        assertThat(outcomes).singleElement()
                .extracting(PipelineRunService.ChildOutcome::disposition)
                .isEqualTo(PipelineRunService.Disposition.BLOCKED);
        assertThat(outcomes.get(0).ownedByThisScan()).isFalse();
        // Ни переочереди, ни смены владельца: прогон ждёт ручного retry как есть.
        verify(runRepository, never()).markRetrying(42L);
        verify(runRepository, never()).removeChildFromSnapshot(anyLong(), anyLong());
        assertThat(zombie.getParentRunId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("Первый скан, встретивший блокировку, проставляет blocked_at — от него считается срок")
    void stampsBlockedAtOnTheFirstScanThatMeetsTheBlock() {
        PipelineRun zombie = existingRunFor("uid-1", run -> {
            run.setId(42L);
            run.setStatus("failed");
            run.setRetryCount(3);
        });
        LocalDateTime before = LocalDateTime.now();

        fanOut("uid-1");

        assertThat(zombie.getBlockedAt()).isNotNull().isAfterOrEqualTo(before);
        verify(runRepository).save(zombie);
        verify(runRepository, never()).markRetrying(42L);
    }

    @Test
    @DisplayName("Следующие сканы не переписывают blocked_at — возраст блокировки не обнуляется")
    void keepsTheOriginalBlockedAtOnLaterScans() {
        LocalDateTime blockedAt = LocalDateTime.now().minusHours(5);
        PipelineRun zombie = existingRunFor("uid-1", run -> {
            run.setId(42L);
            run.setStatus("failed");
            run.setRetryCount(3);
            run.setBlockedAt(blockedAt);
        });

        List<PipelineRunService.ChildOutcome> outcomes = fanOut("uid-1");

        assertThat(outcomes).singleElement()
                .extracting(PipelineRunService.ChildOutcome::disposition)
                .isEqualTo(PipelineRunService.Disposition.BLOCKED);
        assertThat(zombie.getBlockedAt()).isEqualTo(blockedAt);
        verify(runRepository, never()).save(zombie);
        verify(runRepository, never()).markRetrying(42L);
    }

    @Test
    @DisplayName("QA-4: упавший с остатком ретраев переочереживается и переходит к новому скану")
    void requeuesRetryableRunAndTakesOwnership() {
        PipelineRun failed = existingRunFor("uid-1", run -> {
            run.setId(42L);
            run.setStatus("failed");
            run.setRetryCount(1);
            run.setParentRunId(100L);
        });

        List<PipelineRunService.ChildOutcome> outcomes = fanOut("uid-1");

        assertThat(outcomes).singleElement()
                .extracting(PipelineRunService.ChildOutcome::disposition)
                .isEqualTo(PipelineRunService.Disposition.REQUEUED);
        verify(runRepository).markRetrying(42L);
        verify(runRepository).removeChildFromSnapshot(100L, 42L);
        assertThat(failed.getParentRunId()).isEqualTo(SCAN_ID);
    }

    @Test
    @DisplayName("QA-4: незавершённый прогон предыдущего скана остаётся за ним, а не дублируется в новый")
    void leavesInFlightRunWithItsOwnScan() {
        PipelineRun inFlight = existingRunFor("uid-1", run -> {
            run.setId(42L);
            run.setStatus("transforming");
            run.setParentRunId(100L);
        });

        List<PipelineRunService.ChildOutcome> outcomes = fanOut("uid-1");

        assertThat(outcomes).singleElement()
                .extracting(PipelineRunService.ChildOutcome::disposition)
                .isEqualTo(PipelineRunService.Disposition.IN_FLIGHT);
        assertThat(outcomes.get(0).ownedByThisScan()).isFalse();
        verify(runRepository, never()).removeChildFromSnapshot(anyLong(), anyLong());
        assertThat(inFlight.getParentRunId()).isEqualTo(100L);
    }

    private List<PipelineRunService.ChildOutcome> fanOut(String... uids) {
        return service.finishScanWithChildren(SCAN_ID, 1L, String.join(",", uids), null,
                TYPE, 7L, "batch", List.of(uids));
    }

    /** Настраивает, что вернёт lookup существующего прогона по артефакту; {@code null} — ничего. */
    private PipelineRun existingRunFor(String uid, java.util.function.Consumer<PipelineRun> setup) {
        PipelineRun run = null;
        if (setup != null) {
            run = new PipelineRun();
            run.setArtifactUid(uid);
            run.setArtifactType(TYPE);
            setup.accept(run);
        }
        when(runRepository.findFirstByArtifactUidAndArtifactTypeAndStatusNotInOrderByStartedAtDesc(
                eq(uid), eq(TYPE), any())).thenReturn(Optional.ofNullable(run));
        return run;
    }
}
