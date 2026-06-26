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
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.service.PipelineRunService;
import ru.beeline.staging.worker.PreAdapterWorker;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ExternalTaskService     externalTaskService;
    private final HistoryService          historyService;
    private final ConfigurationRepository configurationRepository;
    private final PreAdapterWorker        preAdapterWorker;
    private final PipelineRunService      pipelineRunService;
    private final PipelineRunRepository   pipelineRunRepository;

    @PostMapping("/scan/e2e")
    public ResponseEntity<Map<String, Object>> scanE2E() {
        List<Configuration> configs = configurationRepository.findByArtifactTypeAndIsActiveTrue("e2e-sequence");
        String batchId = UUID.randomUUID().toString();
        int published = 0;
        for (Configuration config : configs) {
            published += preAdapterWorker.runForConfig(config, batchId);
        }
        log.info("Manual e2e scan published {} artifact(s)", published);
        return ResponseEntity.accepted().body(Map.of("publishedCount", published));
    }

    @PostMapping("/external-tasks/{taskId}/retry")
    public ResponseEntity<Void> retryExternalTask(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "3") int retries) {
        externalTaskService.setRetries(taskId, retries);
        log.info("Reset retries={} for external task {}", retries, taskId);
        return ResponseEntity.accepted().build();
    }

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
            String batchId = UUID.randomUUID().toString();
            int found = preAdapterWorker.runForConfig(config, batchId);
            log.info("Re-ran scan for pipelineRunId={}: found={}, new batchId={}", runId, found, batchId);
            return ResponseEntity.accepted().body(Map.of("found", found, "batchId", batchId));
        }

        int tasksReset = pipelineRunService.retryFailedRun(runId, retries);
        log.info("Retry requested for pipelineRunId={}: {} task(s) reset", runId, tasksReset);
        return ResponseEntity.accepted().body(Map.of("tasksReset", tasksReset));
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
