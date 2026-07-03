package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.notice.NoticeType;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.ArtifactNoticeService;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.worker.PipelineTickScheduler;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ExternalTaskService     externalTaskService;
    private final HistoryService          historyService;
    private final ConfigurationRepository configurationRepository;
    private final PipelineTickScheduler   pipelineTickScheduler;
    private final PipelineRunService      pipelineRunService;
    private final PipelineRunRepository   pipelineRunRepository;
    private final ArtifactNoticeService   noticeService;

    @PostMapping("/scan/e2e")
    public ResponseEntity<Map<String, Object>> scanE2E() {
        List<Configuration> configs = configurationRepository.findByArtifactTypeAndIsActiveTrue("e2e-sequence");
        int started = 0;
        for (Configuration config : configs) {
            pipelineTickScheduler.startScan(config);
            started++;
        }
        log.info("Manually started {} scan(s)", started);
        return ResponseEntity.accepted().body(Map.of("startedCount", started));
    }

    @PostMapping("/external-tasks/{taskId}/retry")
    public ResponseEntity<Void> retryExternalTask(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "3") int retries) {
        externalTaskService.setRetries(taskId, retries);
        log.info("Reset retries={} for external task {}", retries, taskId);
        return ResponseEntity.accepted().build();
    }

    /**
     * Retries a failed pipeline_runs row. A scan-attempt row (artifactUid == null — pre-adapter
     * couldn't even reach the source / had no module configured) has no single Camunda task to
     * reset retries on, so it's just re-run from scratch; an artifact row delegates to
     * PipelineRunService.retryFailedRun (resets retries on the specific multi-instance
     * iteration it's stuck at, via executionId).
     */
    @PostMapping("/pipeline-runs/{runId}/retry")
    public ResponseEntity<Map<String, Object>> retryPipelineRun(
            @PathVariable Long runId,
            @RequestParam(defaultValue = "3") int retries) {
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

        int tasksReset = pipelineRunService.retryFailedRun(runId, retries);
        log.info("Retry requested for pipelineRunId={}: {} task(s) reset", runId, tasksReset);
        return ResponseEntity.accepted().body(Map.of("tasksReset", tasksReset));
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

        List<HistoricProcessInstance> instances = historyService
                .createHistoricProcessInstanceQuery()
                .processDefinitionKey("artifact-pipeline-process")
                .variableValueEquals("configurationId", configurationId)
                .orderByProcessInstanceEndTime().desc()
                .listPage(0, limit);

        List<Map<String, Object>> result = instances.stream()
                .map(p -> Map.<String, Object>of(
                        "processInstanceId", p.getId(),
                        "startTime",         p.getStartTime(),
                        "endTime",           p.getEndTime() != null ? p.getEndTime() : "running",
                        "state",             p.getState()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
