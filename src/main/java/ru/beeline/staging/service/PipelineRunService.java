package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.ExternalTask;
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
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineRunService {

    private final PipelineRunRepository      runRepository;
    private final PipelineStageLogRepository stageLogRepository;
    private final ArtifactBatchRepository    batchRepository;
    private final RuntimeService             runtimeService;
    private final ExternalTaskService        externalTaskService;
    private final ObjectMapper               objectMapper;
    private final PipelineDefinitionEntryRepository pipelineDefinitionRepository;

    @Transactional
    public PipelineRun startArtifactPipeline(Long configurationId, String artifactType, String artifactUid,
                                              String batchId, Long scanRunId, Map<String, Object> metadata) {
        PipelineRun run = createRun(artifactUid, artifactType, configurationId, batchId, scanRunId);

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
    public PipelineRun startScanRun(Long configurationId, String artifactType, String batchId) {
        return createRun(null, artifactType, configurationId, batchId, null);
    }

    @Transactional
    public void bindCamundaPid(Long runId, String camundaPid) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setCamundaPid(camundaPid);
            run.setStatus("loading");
            runRepository.save(run);
        });
    }

    public boolean isAlreadyCompleted(Long runId) {
        return runRepository.findById(runId)
                .map(run -> "completed".equals(run.getStatus()))
                .orElse(false);
    }

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

    @Transactional
    public int retryFailedRun(Long runId, int retries) {
        PipelineRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        if (!"failed".equals(run.getStatus())) {
            throw new IllegalStateException("PipelineRun " + runId + " is not failed (status=" + run.getStatus() + ")");
        }
        if (run.getCamundaPid() == null) {
            throw new IllegalStateException("PipelineRun " + runId + " has no camundaPid to retry");
        }

        List<ExternalTask> tasks = externalTaskService.createExternalTaskQuery()
                .processInstanceId(run.getCamundaPid())
                .list();
        tasks.forEach(t -> externalTaskService.setRetries(t.getId(), retries));

        if (!tasks.isEmpty()) {
            runRepository.markRetrying(runId);
        }
        log.info("Retry requested for pipelineRunId={}, camundaPid={}: {} external task(s) reset",
                runId, run.getCamundaPid(), tasks.size());
        return tasks.size();
    }

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
