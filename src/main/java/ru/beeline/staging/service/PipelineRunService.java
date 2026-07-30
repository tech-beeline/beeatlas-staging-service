package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.ExternalTask;
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
    private final ExternalTaskService        externalTaskService;
    private final PipelineDefinitionEntryRepository pipelineDefinitionRepository;
    private final ArtifactNoticeService      noticeService;

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

    @Transactional
    public void bindExecution(Long runId, String processInstanceId, String executionId) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setCamundaPid(processInstanceId);
            run.setExecutionId(executionId);
            runRepository.save(run);
        });
    }

    public boolean isAlreadyCompleted(Long runId) {
        return runRepository.findById(runId)
                .map(run -> "completed".equals(run.getStatus()))
                .orElse(false);
    }

    // Shared by ValidatorWorker/TransformerWorker/SaverWorker: skip re-processing when this exact
    // content (rawDataRefId, reused via upsertByContentHash while unchanged) was already fully
    // processed by a run that reached "completed". A batch merely existing isn't enough — since the
    // canonical save and fdm-products publish commit independently, a batch can exist for a run that
    // ultimately failed at the publish step, and that run must be retried, not silently skipped.
    public boolean isAlreadyFullyProcessed(String artifactUid, String artifactType, long rawDataRefId) {
        Optional<ArtifactBatch> currentBatch = batchRepository.findByArtifactUidAndArtifactTypeAndCurrentTrue(artifactUid, artifactType);
        return currentBatch
                .filter(b -> Long.valueOf(rawDataRefId).equals(b.getRawDataRefId()))
                .map(ArtifactBatch::getRunId)
                .filter(this::isAlreadyCompleted)
                .isPresent();
    }

    // Retrying after a publish failure re-runs the saver from scratch (see isAlreadyFullyProcessed
    // above — that's intentional, so the publish itself gets retried). But the canonical save for this
    // exact rawDataRefId already committed on the earlier attempt; re-running it too would insert a
    // second, fully redundant set of *_versions/notice/context rows. Callers should reuse this batch
    // instead of calling saveSnapshot again.
    public Optional<ArtifactBatch> findExistingBatchForRef(String artifactUid, String artifactType, Long rawDataRefId) {
        return batchRepository.findTopByArtifactUidAndArtifactTypeAndRawDataRefIdOrderByCreatedAtDesc(
                artifactUid, artifactType, rawDataRefId);
    }

    @Transactional
    public Long startStage(Long runId, String stageName, String inputData) {
        PipelineRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        run.setStatus(stageToStatus(stageName));
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

    @Transactional
    public void failStage(Long stageLogId, Long runId, String stageName, String errorMessage) {
        stageLogRepository.findById(stageLogId).ifPresent(entry -> {
            entry.setStatus("failed");
            entry.setCompletedAt(LocalDateTime.now());
            entry.setFailureReason(errorMessage);
            stageLogRepository.save(entry);
        });
        runRepository.markFailed(runId, errorMessage, stageName);
    }

    @Transactional
    public void completeRun(Long runId) {
        runRepository.markCompleted(runId, "completed");
    }

    @Transactional
    public int retryFailedRun(Long runId, int retries) {
        PipelineRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        if (!"failed".equals(run.getStatus())) {
            throw new IllegalStateException("PipelineRun " + runId + " is not failed (status=" + run.getStatus() + ")");
        }
        if (run.getExecutionId() == null && run.getCamundaPid() == null) {
            throw new IllegalStateException("PipelineRun " + runId + " has no execution to retry");
        }

        var query = externalTaskService.createExternalTaskQuery();
        if (run.getExecutionId() != null) {
            query.executionId(run.getExecutionId());
        } else {
            query.processInstanceId(run.getCamundaPid());
        }
        List<ExternalTask> tasks = query.list();
        tasks.forEach(t -> externalTaskService.setRetries(t.getId(), retries));

        if (!tasks.isEmpty()) {
            runRepository.markRetrying(runId);
        }
        log.info("Retry requested for pipelineRunId={}, executionId={}: {} external task(s) reset",
                runId, run.getExecutionId(), tasks.size());
        return tasks.size();
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
