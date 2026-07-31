/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.service.PipelineService;

import java.util.List;

@RestController
@RequestMapping("/configurations")
@RequiredArgsConstructor
public class ConfigurationController {

    private final ConfigurationRepository configurationRepository;
    private final PipelineService         pipelineService;

    @GetMapping
    public ResponseEntity<List<Configuration>> getAll(
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) String artifactType) {

        List<Configuration> result;
        if (artifactType != null) {
            result = configurationRepository.findByArtifactTypeAndIsActiveTrue(artifactType);
        } else if (Boolean.TRUE.equals(isActive)) {
            result = configurationRepository.findByIsActiveTrueAndScheduleIntervalSecondsIsNotNull();
        } else {
            result = configurationRepository.findAll();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Configuration> getById(@PathVariable Long id) {
        return configurationRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<Void> run(@PathVariable Long id) {
        pipelineService.run(id);
        return ResponseEntity.accepted().build();
    }
}
