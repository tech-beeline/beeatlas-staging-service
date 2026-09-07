/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.notice.NoticeType;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.ArtifactNoticeService;
import ru.beeline.staging.service.PipelineExecutionService;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.worker.PipelineTickScheduler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ConfigurationRepository  configurationRepository;
    private final PipelineTickScheduler    pipelineTickScheduler;
    private final PipelineExecutionService pipelineExecutionService;
    private final PipelineRunService       pipelineRunService;
    private final PipelineRunRepository    pipelineRunRepository;
    private final ArtifactNoticeService    noticeService;

    @Value("${staging.recovery.max-auto-retries:3}")
    private int maxAutoRetries;

    @PostMapping("/scan/e2e")
    public ResponseEntity<Map<String, Object>> scanE2E() {
        List<Configuration> configs = configurationRepository.findByArtifactTypeAndIsActiveTrue("e2e-sequence");
        int started = 0;
        int skipped = 0;
        for (Configuration config : configs) {
            if (pipelineTickScheduler.isAlreadyRunning(config)) {
                log.info("Skip configId={} — scan already running", config.getId());
                skipped++;
                continue;
            }
            pipelineTickScheduler.startScan(config);
            started++;
        }
        log.info("Manually started {} scan(s), skipped {} already running", started, skipped);
        return ResponseEntity.accepted().body(Map.of("startedCount", started, "skippedCount", skipped));
    }

    // Scan row (artifactUid == null): re-run from scratch. Artifact row: resume from the first
    // incomplete stage, same as PipelineResumeScheduler.
    @PostMapping("/pipeline-runs/{runId}/retry")
    public ResponseEntity<Map<String, Object>> retryPipelineRun(@PathVariable Long runId) {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));

        if (run.getArtifactUid() == null) {
            if (!"failed".equals(run.getStatus())) {
                throw new IllegalStateException("PipelineRun " + runId + " is not failed (status=" + run.getStatus() + ")");
            }
            Configuration config = configurationRepository.findById(run.getConfigurationId())
                    .orElseThrow(() -> new NoSuchElementException("Configuration not found: " + run.getConfigurationId()));
            pipelineTickScheduler.startScan(config);
            log.info("Re-ran scan for pipelineRunId={}", runId);
            return ResponseEntity.accepted().body(Map.of("restarted", true));
        }

        String configCode = configurationRepository.findById(run.getConfigurationId())
                .map(Configuration::getCode).orElse(null);
        pipelineRunService.retryFailedRun(runId);
        pipelineExecutionService.submitArtifactChain(runId, run.getArtifactType(), configCode);
        log.info("Retried pipelineRunId={}", runId);
        return ResponseEntity.accepted().body(Map.of("retried", true));
    }

    // Runs that failed and used up staging.recovery.max-auto-retries are terminal until a human acts:
    // the scheduler's auto-retry sweep skips them, and a scan that re-finds the artifact no longer
    // adopts them (see PipelineRunService#finishScanWithChildren). Nothing else surfaces them, so
    // without this listing an artifact could silently stop being processed for weeks (defect QA-1).
    @GetMapping("/pipeline-runs/blocked")
    public ResponseEntity<Map<String, Object>> listBlockedRuns(
            @RequestParam(required = false) String artifactType,
            @RequestParam(defaultValue = "100") int limit) {

        List<PipelineRun> blocked = pipelineRunRepository.findBlocked(
                artifactType, maxAutoRetries, PageRequest.of(0, limit));
        List<Map<String, Object>> items = blocked.stream()
                .map(run -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("runId", run.getId());
                    item.put("artifactUid", run.getArtifactUid());
                    item.put("artifactType", run.getArtifactType());
                    item.put("failedStage", run.getFailedStage());
                    item.put("failureReason", run.getFailureReason());
                    item.put("retryCount", run.getRetryCount());
                    item.put("startedAt", run.getStartedAt());
                    return item;
                })
                .toList();
        return ResponseEntity.ok(Map.of("count", items.size(), "items", items));
    }

    /** Bulk counterpart of {@link #retryPipelineRun} — clears the blocked backlog after its cause is fixed. */
    @PostMapping("/pipeline-runs/retry-blocked")
    public ResponseEntity<Map<String, Object>> retryBlockedRuns(
            @RequestParam(required = false) String artifactType,
            @RequestParam(defaultValue = "100") int limit) {

        List<PipelineRun> blocked = pipelineRunRepository.findBlocked(
                artifactType, maxAutoRetries, PageRequest.of(0, limit));
        int retried = 0;
        for (PipelineRun run : blocked) {
            String configCode = configurationRepository.findById(run.getConfigurationId())
                    .map(Configuration::getCode).orElse(null);
            try {
                pipelineRunService.retryFailedRun(run.getId());
                pipelineExecutionService.submitArtifactChain(run.getId(), run.getArtifactType(), configCode);
                retried++;
            } catch (Exception e) {
                log.warn("Could not requeue blocked pipelineRunId={}", run.getId(), e);
            }
        }
        log.info("Requeued {} of {} blocked run(s), artifactType={}", retried, blocked.size(), artifactType);
        return ResponseEntity.accepted().body(Map.of("retriedCount", retried, "selectedCount", blocked.size()));
    }

    @GetMapping("/notice-types")
    public ResponseEntity<List<NoticeType>> listNoticeTypes(
            @RequestParam(defaultValue = "pending") String state) {
        return ResponseEntity.ok(noticeService.listByState(state));
    }

    @PostMapping("/notice-types/{code}/confirm")
    public ResponseEntity<NoticeType> confirmNoticeType(
            @PathVariable String code,
            @RequestParam String confirmedBy) {
        return ResponseEntity.ok(noticeService.confirmNoticeType(code, confirmedBy));
    }

    @PostMapping("/notice-types/{code}/reject")
    public ResponseEntity<Void> rejectNoticeType(
            @PathVariable String code,
            @RequestParam String rejectedBy) {
        noticeService.rejectNoticeType(code, rejectedBy);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/configurations/{configurationId}/history")
    public ResponseEntity<List<Map<String, Object>>> history(
            @PathVariable Long configurationId,
            @RequestParam(defaultValue = "20") int limit) {

        List<PipelineRun> scans = pipelineRunRepository
                .findByConfigurationIdAndArtifactUidIsNullOrderByStartedAtDesc(
                        configurationId, PageRequest.of(0, limit));

        List<Map<String, Object>> result = scans.stream()
                .map(p -> Map.<String, Object>of(
                        "pipelineRunId", p.getId(),
                        "startTime",     p.getStartedAt(),
                        "endTime",       p.getCompletedAt() != null ? p.getCompletedAt() : "running",
                        "state",         p.getStatus()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
