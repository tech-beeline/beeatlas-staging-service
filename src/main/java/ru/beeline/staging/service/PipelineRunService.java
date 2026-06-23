package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.PipelineStageLog;
import ru.beeline.staging.repository.ArtifactBatchRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.PipelineStageLogRepository;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Manages observability records for each pipeline run.
 * Called at key moments: run creation (EventDispatcher), stage start/complete/fail
 * (each worker), and batch creation (SaverWorker on successful canonical save).
 *
 * All writes are in separate short transactions so that a monitoring write failure
 * never rolls back the pipeline logic it's observing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineRunService {

    private final PipelineRunRepository      runRepository;
    private final PipelineStageLogRepository stageLogRepository;
    private final ArtifactBatchRepository    batchRepository;

    /** Called by EventDispatcher before starting the Camunda process instance. */
    @Transactional
    public PipelineRun createRun(String artifactUid, String artifactType, Long configurationId) {
        PipelineRun run = new PipelineRun();
        run.setArtifactUid(artifactUid);
        run.setArtifactType(artifactType);
        run.setConfigurationId(configurationId);
        run.setStatus("pending");
        run.setStartedAt(LocalDateTime.now());
        return runRepository.save(run);
    }

    /** Called by EventDispatcher after Camunda process instance is started, to store camundaPid. */
    @Transactional
    public void bindCamundaPid(Long runId, String camundaPid) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setCamundaPid(camundaPid);
            run.setStatus("loading");
            runRepository.save(run);
        });
    }

    /**
     * Called by each worker at the beginning of its execution.
     * Returns the stage log id — workers pass it to completeStage / failStage.
     */
    @Transactional
    public Long startStage(Long runId, String stageName) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(stageToStatus(stageName));
            runRepository.save(run);
        });
        PipelineStageLog log = new PipelineStageLog();
        log.setRunId(runId);
        log.setStageName(stageName);
        log.setStatus("running");
        log.setStartedAt(LocalDateTime.now());
        return stageLogRepository.save(log).getId();
    }

    @Transactional
    public void completeStage(Long stageLogId, Map<String, Object> summary) {
        stageLogRepository.findById(stageLogId).ifPresent(entry -> {
            entry.setStatus("completed");
            entry.setCompletedAt(LocalDateTime.now());
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

    /**
     * Called by CanonicalModelSaverService after successfully persisting a snapshot.
     * Creates the ArtifactBatch record and marks previous batches as non-current.
     */
    @Transactional
    public ArtifactBatch createBatch(String artifactUid, String artifactType,
                                     Long runId, Long rawDataRefId,
                                     int biStepsCount, int interfacesCount, int operationsCount) {
        batchRepository.clearCurrentFlag(artifactUid, artifactType);

        ArtifactBatch batch = new ArtifactBatch();
        batch.setArtifactUid(artifactUid);
        batch.setArtifactType(artifactType);
        batch.setRunId(runId);
        batch.setRawDataRefId(rawDataRefId);
        batch.setBiStepsCount(biStepsCount);
        batch.setInterfacesCount(interfacesCount);
        batch.setOperationsCount(operationsCount);
        batch.setCurrent(true);
        batch.setCreatedAt(LocalDateTime.now());

        ArtifactBatch saved = batchRepository.save(batch);
        log.info("Created artifact batch id={} for uid={} type={} (biSteps={}, ifaces={}, ops={})",
                saved.getId(), artifactUid, artifactType, biStepsCount, interfacesCount, operationsCount);
        return saved;
    }

    private static String stageToStatus(String stageName) {
        return switch (stageName) {
            case "loader"      -> "loading";
            case "validator"   -> "validating";
            case "transformer" -> "transforming";
            case "saver"       -> "saving";
            case "publisher"   -> "publishing";
            default            -> stageName;
        };
    }
}
