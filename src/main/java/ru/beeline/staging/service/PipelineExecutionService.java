/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ru.beeline.staging.config.PipelineExecutors;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.PipelineStageLog;
import ru.beeline.staging.pipeline.PipelineDefinitions;
import ru.beeline.staging.pipeline.exec.ArtifactPipelineStage;
import ru.beeline.staging.pipeline.exec.PreAdapterStage;
import ru.beeline.staging.pipeline.preadapter.ArtifactPreAdapter;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.PipelineStageLogRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineExecutionService {

    private final PipelineRunService          pipelineRunService;
    private final PipelineRunRepository       pipelineRunRepository;
    private final PipelineStageLogRepository  pipelineStageLogRepository;
    private final PreAdapterStage             preAdapterStage;
    private final List<ArtifactPipelineStage> stages;
    private final SourceArtefactService       sourceArtefactService;
    private final MeterRegistry               meterRegistry;
    private final PipelineExecutors            pipelineExecutors;

    @Value("${staging.executor.lease-duration-ms:300000}")
    private long leaseDurationMs;

    private Map<String, ArtifactPipelineStage> stageByName;
    private String ownerId;

    @PostConstruct
    void init() {
        stageByName = stages.stream().collect(Collectors.toMap(ArtifactPipelineStage::stageName, s -> s));
        String host = System.getenv().getOrDefault("HOSTNAME", "local");
        ownerId = host + "-" + UUID.randomUUID();
        log.info("PipelineExecutionService ownerId={}", ownerId);
    }


    public void submitScan(Configuration config) {
        dispatch(pipelineExecutors.scan(), "scan for configId=" + config.getId(), () -> runScan(config));
    }

    public void submitResumeScan(PipelineRun scan, Configuration config) {
        dispatch(pipelineExecutors.scan(), "resume scan runId=" + scan.getId(), () -> resumeScan(scan, config));
    }

    public void submitArtifactChain(Long runId, String artifactType, String configCode) {
        dispatch(pipelineExecutors.artifact(configCode), "artifact chain runId=" + runId,
                () -> runArtifactChain(runId, artifactType));
    }


    void runScan(Configuration config) {
        PipelineRun scan = createScanRun(config);
        if (scan == null) return;
        if (!claim(scan.getId())) {
            log.warn("Could not claim newly created scan run {} for configId={} — unexpected", scan.getId(), config.getId());
            return;
        }
        executeScan(scan, config);
    }

    void resumeScan(PipelineRun scan, Configuration config) {
        if (!claim(scan.getId())) {
            log.debug("Scan run {} already claimed elsewhere, skipping", scan.getId());
            return;
        }
        executeScan(scan, config);
    }

    void runArtifactChain(Long runId, String artifactType) {
        if (!claim(runId)) {
            log.debug("Run {} already claimed elsewhere, skipping", runId);
            return;
        }

        Set<String> completedStages = completedStagesOf(runId);
        for (String stageName : PipelineDefinitions.STAGE_ORDER) {
            if ("pre-adapter".equals(stageName) || completedStages.contains(stageName)) continue;
            if (!executeStageWithMetrics(stageName, runId, artifactType)) return;
        }
    }

    private void executeScan(PipelineRun scan, Configuration config) {
        PreAdapterStage.ScanOutcome outcome = tryScan(scan, config);
        if (outcome == null) return;

        List<ArtifactPreAdapter.FoundArtifact> found = outcome.found();
        List<String> uids = found.stream().map(ArtifactPreAdapter.FoundArtifact::uid).toList();
        List<PipelineRun> children = pipelineRunService.finishScanWithChildren(
                scan.getId(), outcome.stageLogId(), String.join(",", uids), Map.of("foundCount", found.size()),
                config.getArtifactType(), config.getId(), scan.getBatchId(), uids);

        dispatchChildren(scan, config, found, children);
    }


    private void dispatch(Executor executor, String description, Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Unhandled exception running {}", description, e);
            }
        });
    }

    private PipelineRun createScanRun(Configuration config) {
        try {
            return pipelineRunService.createRun(
                    null, config.getArtifactType(), config.getId(), UUID.randomUUID().toString(), null);
        } catch (DataIntegrityViolationException e) {
            log.debug("Skip configId={} — another instance already started a scan for it", config.getId());
            return null;
        }
    }

    private PreAdapterStage.ScanOutcome tryScan(PipelineRun scan, Configuration config) {
        try {
            return preAdapterStage.scan(scan, config);
        } catch (Exception e) {
            log.warn("Scan failed for configId={}, scanRunId={}", config.getId(), scan.getId(), e);
            return null;
        }
    }

    private void dispatchChildren(PipelineRun scan, Configuration config,
                                   List<ArtifactPreAdapter.FoundArtifact> found, List<PipelineRun> children) {
        for (int i = 0; i < found.size(); i++) {
            ArtifactPreAdapter.FoundArtifact item = found.get(i);
            PipelineRun child = children.get(i);
            sourceArtefactService.recordSeen(config, item.uid(), scan.getId(), child.getId(), artifactNameOf(item));
            submitArtifactChain(child.getId(), config.getArtifactType(), config.getCode());
        }
    }

    private Set<String> completedStagesOf(Long runId) {
        return pipelineStageLogRepository.findByRunIdOrderByStartedAt(runId).stream()
                .filter(l -> "completed".equals(l.getStatus()))
                .map(PipelineStageLog::getStageName)
                .collect(Collectors.toSet());
    }

    private boolean executeStageWithMetrics(String stageName, Long runId, String artifactType) {
        ArtifactPipelineStage stage = stageByName.get(stageName);
        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "completed";
        try {
            stage.execute(runId);
            return true;
        } catch (Exception e) {
            status = "failed";
            log.warn("Stage {} failed for runId={}", stageName, runId, e);
            meterRegistry.counter("staging_pipeline_errors_total", "stage", stageName, "artifact_type", artifactType).increment();
            return false;
        } finally {
            sample.stop(Timer.builder("staging_pipeline_stage_duration_seconds")
                    .tag("stage", stageName)
                    .tag("artifact_type", artifactType)
                    .tag("status", status)
                    .register(meterRegistry));
        }
    }

    private boolean claim(Long runId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = pipelineRunRepository.claim(runId, ownerId, now.plus(Duration.ofMillis(leaseDurationMs)), now);
        return updated == 1;
    }

    private static String artifactNameOf(ArtifactPreAdapter.FoundArtifact item) {
        Map<String, Object> meta = item.metadata();
        if (meta == null) return null;
        Object nameObj = meta.get("name");
        if (nameObj == null) nameObj = meta.get("productName");
        return nameObj != null ? nameObj.toString() : null;
    }
}
