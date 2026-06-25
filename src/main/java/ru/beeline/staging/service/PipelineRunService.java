package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.domain.PipelineDefinitionEntry;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.PipelineStageLog;
import ru.beeline.staging.repository.ArtifactBatchRepository;
import ru.beeline.staging.repository.PipelineDefinitionEntryRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.PipelineStageLogRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages observability records for each pipeline run, and is the single entry point
 * for starting an artifact-pipeline-process instance (used by both the preAdapter fan-out
 * and the manual /configurations/{id}/run trigger) — no RabbitMQ/EventDispatcher hop
 * needed, runtimeService.startProcessInstanceByMessage(...) is itself a durable, committed
 * action in Camunda's own Postgres tables.
 *
 * All monitoring writes are in separate short transactions so that a monitoring write
 * failure never rolls back the pipeline logic it's observing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineRunService {

    private final PipelineRunRepository      runRepository;
    private final PipelineStageLogRepository stageLogRepository;
    private final ArtifactBatchRepository    batchRepository;
    private final RuntimeService             runtimeService;
    private final ObjectMapper               objectMapper;
    private final PipelineDefinitionEntryRepository pipelineDefinitionRepository;

    /**
     * Creates the PipelineRun tracking record and starts artifact-pipeline-process for it.
     * Each call is independent and idempotent-safe: if the caller crashes before this
     * call, nothing was started yet (no data loss, simply not-yet-attempted); if it
     * crashes right after, the process instance is already durably recorded by Camunda.
     */
    @Transactional
    public PipelineRun startArtifactPipeline(Long configurationId, String artifactType, String artifactUid,
                                              String batchId, Map<String, Object> metadata) {
        PipelineRun run = createRun(artifactUid, artifactType, configurationId, batchId);

        // pre-adapter already found this artifact by the time this run is created (that's
        // what triggered this call) — log it as an already-completed stage 1, so the whole
        // chain pre-adapter -> adapter -> ... -> saver lives under one run_id instead of a
        // separate pre-adapter-only run.
        Long preAdapterStageLogId = startStage(run.getId(), "pre-adapter", metadata != null ? metadata : Map.of());
        completeStage(preAdapterStageLogId, metadata, null);

        Map<String, Object> variables = new HashMap<>();
        variables.put("artifactType",    artifactType);
        variables.put("artifactUid",     artifactUid);
        variables.put("configurationId", configurationId);
        variables.put("batchId",         batchId);
        variables.put("pipelineRunId",   run.getId());
        if (metadata != null) {
            try {
                variables.put("metadataJson", objectMapper.writeValueAsString(metadata));
            } catch (Exception e) {
                log.warn("Failed to serialize metadata for uid={}", artifactUid, e);
            }
        }

        ProcessInstance pi = runtimeService.startProcessInstanceByMessage("artifact.ready", artifactUid, variables);
        bindCamundaPid(run.getId(), pi.getId());
        log.info("Started artifact-pipeline-process for uid={}, pipelineRunId={}, camundaPid={}",
                artifactUid, run.getId(), pi.getId());
        return run;
    }

    @Transactional
    public PipelineRun createRun(String artifactUid, String artifactType, Long configurationId, String batchId) {
        PipelineRun run = new PipelineRun();
        run.setArtifactUid(artifactUid);
        run.setArtifactType(artifactType);
        run.setConfigurationId(configurationId);
        run.setBatchId(batchId);
        run.setStatus("pending");
        run.setStartedAt(LocalDateTime.now());
        run.setPipelineDefinitionId(pipelineDefinitionRepository.findByArtifactTypeAndCurrentTrue(artifactType)
                .map(PipelineDefinitionEntry::getId)
                .orElse(null));
        return runRepository.save(run);
    }

    @Transactional
    public void bindCamundaPid(Long runId, String camundaPid) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setCamundaPid(camundaPid);
            run.setStatus("loading");
            runRepository.save(run);
        });
    }

    /** Idempotency guard for SaverWorker: true if this run already reached a terminal save. */
    public boolean isAlreadyCompleted(Long runId) {
        return runRepository.findById(runId)
                .map(run -> "completed".equals(run.getStatus()))
                .orElse(false);
    }

    /**
     * Called by AbstractWorker at the beginning of each stage execution, with the task's
     * full input variables. Returns the stage log id — workers pass it to completeStage /
     * failStage.
     */
    @Transactional
    public Long startStage(Long runId, String stageName, Map<String, Object> inputData) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(stageToStatus(stageName));
            runRepository.save(run);
        });
        PipelineStageLog log = new PipelineStageLog();
        log.setRunId(runId);
        log.setStageName(stageName);
        log.setStatus("running");
        log.setInputData(inputData);
        log.setStartedAt(LocalDateTime.now());
        return stageLogRepository.save(log).getId();
    }

    /**
     * outputData is the full map returned by the stage module; summary is the lightweight
     * scalar-only subset (kept for quick dashboards, see AbstractWorker.buildSummary).
     */
    @Transactional
    public void completeStage(Long stageLogId, Map<String, Object> outputData, Map<String, Object> summary) {
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

    /**
     * Called by an ArtifactSaver (e.g. E2ECanonicalSaver) after successfully persisting a snapshot.
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
