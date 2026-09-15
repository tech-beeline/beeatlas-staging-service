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
import ru.beeline.staging.exception.PipelineRunCancelledException;
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

    private static final List<String> DONE_STATUSES = List.of("completed", "cancelled");

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
        runRepository.updateRawDataRefId(runId, rawDataRefId);
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
        if (runRepository.advanceStage(runId, stageToStatus(stageName)) == 0) {
            throw new PipelineRunCancelledException(runId, stageName);
        }

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
            runRepository.resetResumeCount(entry.getRunId());
        });
    }

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

    public enum Disposition {
        CREATED,
        REQUEUED,
        IN_FLIGHT,
        BLOCKED
    }

    public record ChildOutcome(String artifactUid, PipelineRun run, Disposition disposition) {
        public boolean ownedByThisScan() {
            return disposition == Disposition.CREATED || disposition == Disposition.REQUEUED;
        }
    }

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
        runRepository.resetResumeCount(scanRunId);
        meterRegistry.counter("staging_pipeline_runs_total", "artifact_type", artifactType, "status", "completed").increment();

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
                if (existing.getBlockedAt() == null) {
                    existing.setBlockedAt(LocalDateTime.now());
                    runRepository.save(existing);
                }
                outcomes.add(new ChildOutcome(uid, existing, Disposition.BLOCKED));
                continue;
            }
            runRepository.markRetrying(existing.getId());
            reassignParent(existing, scanRunId);
            outcomes.add(new ChildOutcome(uid, existing, Disposition.REQUEUED));
        }
        return outcomes;
    }

    private void reassignParent(PipelineRun run, Long newParentRunId) {
        Long previousParent = run.getParentRunId();
        if (newParentRunId.equals(previousParent)) return;
        if (previousParent != null) {
            runRepository.removeChildFromSnapshot(previousParent, run.getId());
        }
        runRepository.updateParentRunId(run.getId(), newParentRunId);
    }

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

    private String artifactTypeOf(Long runId) {
        return runRepository.findById(runId).map(PipelineRun::getArtifactType).orElse("unknown");
    }

    @Transactional
    public void failStalledRun(Long runId, int resumeAttempts, int retryCeiling) {
        PipelineRun run = runRepository.findById(runId).orElse(null);
        if (run == null || DONE_STATUSES.contains(run.getStatus()) || "failed".equals(run.getStatus())) return;

        String stage = stageLogRepository.findRunningStageNames(runId).stream().findFirst().orElse(run.getStatus());
        String reason = "Stalled: claimed " + resumeAttempts + " times with no stage completing; "
                + "forced terminal by the stall watchdog";

        int abandoned = stageLogRepository.abandonRunningStages(runId, reason);
        if (runRepository.markStalled(runId, reason, stage, retryCeiling) == 0) return;

        meterRegistry.counter("staging_pipeline_runs_stalled_total",
                "artifact_type", run.getArtifactType(),
                "kind", run.getArtifactUid() == null ? "scan" : "artifact").increment();
        log.error("Stall watchdog force-failed pipelineRunId={} (type={}, {}, configId={}, startedAt={}): "
                        + "{} resume claims, no progress, {} dangling stage log(s) closed. "
                        + "Root cause is upstream of this — check the last '{}' stage failure for this run.",
                runId, run.getArtifactType(), run.getArtifactUid() == null ? "scan" : "artifact=" + run.getArtifactUid(),
                run.getConfigurationId(), run.getStartedAt(), resumeAttempts, abandoned, stage);
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
