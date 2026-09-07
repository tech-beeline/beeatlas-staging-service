/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.PipelineDefinitionEntry;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.PipelineStageLog;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.ArtifactBatchRepository;
import ru.beeline.staging.repository.PipelineDefinitionEntryRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.PipelineStageLogRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineRunService {

    private final PipelineRunRepository      runRepository;
    private final PipelineStageLogRepository stageLogRepository;
    private final ArtifactBatchRepository    batchRepository;
    private final PipelineDefinitionEntryRepository pipelineDefinitionRepository;
    private final ArtifactNoticeService      noticeService;
    private final MeterRegistry              meterRegistry;

    private static final List<String> DONE_STATUSES = List.of("completed");

    @Value("${staging.recovery.max-auto-retries:3}")
    private int maxAutoRetries;

    @Transactional
    public PipelineRun createRun(String artifactUid, String artifactType, Long configurationId, String batchId,
                                  Long scanRunId) {
        PipelineRun run = new PipelineRun();
        run.setArtifactUid(artifactUid);
        run.setArtifactType(artifactType);
        run.setConfigurationId(configurationId);
        run.setBatchId(batchId);
        run.setStatus("pending");
        run.setStartedAt(LocalDateTime.now());
        run.setParentRunId(scanRunId);
        run.setPipelineDefinitionId(pipelineDefinitionRepository.findByArtifactTypeAndCurrentTrue(artifactType)
                .map(PipelineDefinitionEntry::getId)
                .orElse(null));
        return runRepository.save(run);
    }

    @Transactional
    public void setRawDataRefId(Long runId, Long rawDataRefId) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setRawDataRefId(rawDataRefId);
            runRepository.save(run);
        });
    }

    public boolean isAlreadyCompleted(Long runId) {
        return runRepository.findById(runId)
                .map(run -> "completed".equals(run.getStatus()))
                .orElse(false);
    }

    public boolean isAlreadyFullyProcessed(String artifactUid, String artifactType, long rawDataRefId) {
        Optional<ArtifactBatch> currentBatch = batchRepository.findByArtifactUidAndArtifactTypeAndCurrentTrue(artifactUid, artifactType);
        return currentBatch
                .filter(b -> Long.valueOf(rawDataRefId).equals(b.getRawDataRefId()))
                .map(ArtifactBatch::getRunId)
                .filter(this::isAlreadyCompleted)
                .isPresent();
    }

    public Optional<ArtifactBatch> findExistingBatchForRef(String artifactUid, String artifactType, Long rawDataRefId) {
        return batchRepository.findTopByArtifactUidAndArtifactTypeAndRawDataRefIdOrderByCreatedAtDesc(
                artifactUid, artifactType, rawDataRefId);
    }

    @Transactional
    public Long startStage(Long runId, String stageName, String inputData) {
        PipelineRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        run.setStatus(stageToStatus(stageName));
        if (run.getExecutionStartedAt() == null) {
            run.setExecutionStartedAt(LocalDateTime.now());
        }
        runRepository.save(run);

        PipelineStageLog log = new PipelineStageLog();
        log.setRunId(runId);
        log.setScanRunId(run.getParentRunId() != null ? run.getParentRunId() : run.getId());
        log.setStageName(stageName);
        log.setStatus("running");
        log.setInputData(inputData);
        log.setStartedAt(LocalDateTime.now());
        return stageLogRepository.save(log).getId();
    }

    @Transactional
    public void completeStage(Long stageLogId, String outputData, Map<String, Object> summary) {
        stageLogRepository.findById(stageLogId).ifPresent(entry -> {
            entry.setStatus("completed");
            entry.setCompletedAt(LocalDateTime.now());
            entry.setOutputData(outputData);
            entry.setSummaryJson(summary);
            stageLogRepository.save(entry);
        });
    }

    /** Replaces a finished stage's summary — used by fan-out, whose per-artifact counts are only known after it. */
    @Transactional
    public void updateStageSummary(Long stageLogId, Map<String, Object> summary) {
        stageLogRepository.findById(stageLogId).ifPresent(entry -> {
            entry.setSummaryJson(summary);
            stageLogRepository.save(entry);
        });
    }

    @Transactional
    public void failStage(Long stageLogId, Long runId, String stageName, String errorMessage) {
        stageLogRepository.findById(stageLogId).ifPresent(entry -> {
            entry.setStatus("failed");
            entry.setCompletedAt(LocalDateTime.now());
            entry.setFailureReason(errorMessage);
            stageLogRepository.save(entry);
        });
        String artifactType = artifactTypeOf(runId);
        runRepository.markFailed(runId, errorMessage, stageName);
        runRepository.incrementRetryCount(runId);
        meterRegistry.counter("staging_pipeline_runs_total", "artifact_type", artifactType, "status", "failed").increment();
    }

    /** What this scan decided to do with one artifact it found — see {@link #finishScanWithChildren}. */
    public enum Disposition {
        /** No run was queued for this artifact — a fresh one was created and belongs to this scan. */
        CREATED,
        /** A failed run with retries left was re-queued; ownership moved to this scan. */
        REQUEUED,
        /** A previous scan's run for this artifact is still in flight — it keeps that scan's ownership. */
        IN_FLIGHT,
        /** Failed and out of auto-retries: needs POST /admin/pipeline-runs/{id}/retry, nothing to dispatch. */
        BLOCKED
    }

    public record ChildOutcome(String artifactUid, PipelineRun run, Disposition disposition) {
        /** Runs this scan is responsible for executing — the ones that go into its child_run_ids. */
        public boolean ownedByThisScan() {
            return disposition == Disposition.CREATED || disposition == Disposition.REQUEUED;
        }
    }

    // One transaction: completeStage + completeRun + all children, so a crash mid-fan-out can't
    // leave a partial set of children behind.
    @Transactional
    public List<ChildOutcome> finishScanWithChildren(Long scanRunId, Long stageLogId, String outputData,
                                                      Map<String, Object> summary, String artifactType,
                                                      Long configurationId, String batchId,
                                                      List<String> artifactUids) {
        stageLogRepository.findById(stageLogId).ifPresent(entry -> {
            entry.setStatus("completed");
            entry.setCompletedAt(LocalDateTime.now());
            entry.setOutputData(outputData);
            entry.setSummaryJson(summary);
            stageLogRepository.save(entry);
        });
        runRepository.markCompleted(scanRunId, "completed");
        meterRegistry.counter("staging_pipeline_runs_total", "artifact_type", artifactType, "status", "completed").increment();

        // A found artifact may already have an undrained (or failed-but-retryable) run from a
        // previous scan of this config — reuse it instead of piling on a duplicate every cycle.
        // Only "completed" excludes reuse; "failed" is deliberately included (unlike the old
        // NOT IN (completed,failed) check) — otherwise a failed run sitting between failure and
        // the next auto-retry sweep looked "not queued" to this check, so a new scan would create
        // a second copy for the same artifact right alongside it.
        //
        // What reuse must NOT do is hand back a run that can never move again: a "failed" run past
        // maxAutoRetries is terminal until someone retries it by hand, but it still matches the
        // NOT IN ('completed') lookup, so every later scan re-adopted it, listed it as its own
        // child and dispatched a chain that #claim() refuses (claim skips failed/completed). The
        // artifact then stops being processed forever while hundreds of scans keep reporting it as
        // their own failed child. Such runs are reported as BLOCKED here instead — visible, not
        // silently re-adopted (defect QA-1).
        List<ChildOutcome> outcomes = new java.util.ArrayList<>(artifactUids.size());
        for (String uid : artifactUids) {
            PipelineRun existing = runRepository
                    .findFirstByArtifactUidAndArtifactTypeAndStatusNotInOrderByStartedAtDesc(uid, artifactType, DONE_STATUSES)
                    .orElse(null);
            if (existing == null) {
                outcomes.add(new ChildOutcome(uid, createRun(uid, artifactType, configurationId, batchId, scanRunId),
                        Disposition.CREATED));
                continue;
            }
            if (!"failed".equals(existing.getStatus())) {
                outcomes.add(new ChildOutcome(uid, existing, Disposition.IN_FLIGHT));
                continue;
            }
            if (existing.getRetryCount() >= maxAutoRetries) {
                outcomes.add(new ChildOutcome(uid, existing, Disposition.BLOCKED));
                continue;
            }
            runRepository.markRetrying(existing.getId());
            reassignParent(existing, scanRunId);
            outcomes.add(new ChildOutcome(uid, existing, Disposition.REQUEUED));
        }
        return outcomes;
    }

    // A re-queued run is executed on behalf of the scan that re-queued it, so ownership moves with
    // it: parent_run_id is repointed and the run is dropped from the previous scan's child_run_ids.
    // Keeping both sides in step is what makes "which scan did this work" answerable — without it a
    // single run accumulated membership in hundreds of scans' snapshots (defect QA-4).
    private void reassignParent(PipelineRun run, Long newParentRunId) {
        Long previousParent = run.getParentRunId();
        if (newParentRunId.equals(previousParent)) return;
        if (previousParent != null) {
            runRepository.removeChildFromSnapshot(previousParent, run.getId());
        }
        run.setParentRunId(newParentRunId);
        runRepository.save(run);
    }

    // Written once, right after fan-out (see PipelineExecutionService#executeScan) — the only moment
    // "which artifacts did this scan find" is unambiguous. Holds exactly the runs this scan owns
    // (created or re-queued), so it always agrees with the parent_run_id back-references; artifacts
    // whose run is still in flight from an earlier scan, or blocked awaiting a manual retry, are
    // reported in the pre-adapter stage summary instead of being counted here as this scan's work.
    @Transactional
    public void snapshotChildRunIds(Long scanRunId, List<Long> childRunIds) {
        runRepository.findById(scanRunId).ifPresent(scan -> {
            scan.setChildRunIds(childRunIds);
            runRepository.save(scan);
        });
    }

    @Transactional
    public void completeRun(Long runId) {
        String artifactType = artifactTypeOf(runId);
        runRepository.markCompleted(runId, "completed");
        meterRegistry.counter("staging_pipeline_runs_total", "artifact_type", artifactType, "status", "completed").increment();
    }

    // markCompleted/markFailed are bulk UPDATEs and don't return the entity, so fetched separately.
    private String artifactTypeOf(Long runId) {
        return runRepository.findById(runId).map(PipelineRun::getArtifactType).orElse("unknown");
    }

    @Transactional
    public void retryFailedRun(Long runId) {
        PipelineRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        if (!"failed".equals(run.getStatus())) {
            throw new IllegalStateException("PipelineRun " + runId + " is not failed (status=" + run.getStatus() + ")");
        }
        runRepository.markRetrying(runId);
        log.info("Retry requested for pipelineRunId={}", runId);
    }

    @Transactional
    public ArtifactBatch createBatch(String artifactUid, String artifactType,
                                     Long runId, Long rawDataRefId,
                                     int biStepsCount, int interfacesCount, int operationsCount) {
        return createBatch(artifactUid, artifactType, runId, rawDataRefId,
                biStepsCount, interfacesCount, operationsCount, 0, 0);
    }

    @Transactional
    public ArtifactBatch createBatch(String artifactUid, String artifactType,
                                     Long runId, Long rawDataRefId,
                                     int biStepsCount, int interfacesCount, int operationsCount,
                                     int productsCount, int containersCount) {
        batchRepository.clearCurrentFlag(artifactUid, artifactType);

        ArtifactBatch batch = new ArtifactBatch();
        batch.setArtifactUid(artifactUid);
        batch.setArtifactType(artifactType);
        batch.setRunId(runId);
        batch.setRawDataRefId(rawDataRefId);
        batch.setBiStepsCount(biStepsCount);
        batch.setInterfacesCount(interfacesCount);
        batch.setOperationsCount(operationsCount);
        batch.setProductsCount(productsCount);
        batch.setContainersCount(containersCount);
        batch.setCurrent(true);
        batch.setCreatedAt(LocalDateTime.now());

        ArtifactBatch saved = batchRepository.save(batch);
        log.info("Created artifact batch id={} for uid={} type={} (biSteps={}, ifaces={}, ops={}, products={}, containers={})",
                saved.getId(), artifactUid, artifactType, biStepsCount, interfacesCount, operationsCount, productsCount, containersCount);
        return saved;
    }

    public List<ArtifactNotice> saveNotices(Long rawDataRefId, List<ArtifactNotice> notices) {
        return noticeService.saveNotices(rawDataRefId, notices);
    }

    private static String stageToStatus(String stageName) {
        return switch (stageName) {
            case "pre-adapter" -> "pending";
            case "adapter"     -> "loading";
            case "validator"   -> "validating";
            case "transformer" -> "transforming";
            case "saver"       -> "saving";
            case "publisher"   -> "publishing";
            default            -> stageName;
        };
    }
}
