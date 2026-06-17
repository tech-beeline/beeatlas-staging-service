package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.beeline.staging.service.SparxScanService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ExternalTaskService externalTaskService;
    private final HistoryService      historyService;
    private final SparxScanService    sparxScanService;

    /**
     * Manually triggers a Sparx e2e-sequence scan for all active e2e-sequence configurations,
     * without waiting for the pre-adapter-process timer (R/PT6H by default). Bypasses the
     * Camunda already-running/interval-elapsed throttling — intended for dev/testing.
     */
    @PostMapping("/scan/e2e")
    public ResponseEntity<Map<String, Object>> scanE2E() {
        int published = sparxScanService.scanAllActiveE2EConfigurations();
        log.info("Manual e2e scan published {} artifact(s)", published);
        return ResponseEntity.accepted().body(Map.of("publishedCount", published));
    }

    /** Reset retries on a stuck external task so it re-enters the worker poll cycle. */
    @PostMapping("/external-tasks/{taskId}/retry")
    public ResponseEntity<Void> retryExternalTask(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "3") int retries) {
        externalTaskService.setRetries(taskId, retries);
        log.info("Reset retries={} for external task {}", retries, taskId);
        return ResponseEntity.accepted().build();
    }

    /** Last N completed pipeline runs for a given configuration (from Camunda History). */
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
