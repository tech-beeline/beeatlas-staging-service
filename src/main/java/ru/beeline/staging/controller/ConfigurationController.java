package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.beeline.staging.service.PipelineService;

@Slf4j
@RestController
@RequestMapping("/configurations")
@RequiredArgsConstructor
public class ConfigurationController {

    private final PipelineService pipelineService;

    /**
     * POST /configurations/{id}/run
     * Publishes a staging event for the given configuration → full pipeline runs → visible in Camunda Cockpit.
     */
    @PostMapping("/{id}/run")
    public ResponseEntity<Void> run(@PathVariable Long id) {
        pipelineService.run(id);
        return ResponseEntity.accepted().build();
    }
}
