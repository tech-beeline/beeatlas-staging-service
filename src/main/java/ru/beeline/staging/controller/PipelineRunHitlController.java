/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.dto.pipelinerun.CreatePipelineRunRequest;
import ru.beeline.staging.dto.pipelinerun.CreatePipelineRunResponse;
import ru.beeline.staging.service.PipelineRunImportService;

@RestController
@RequestMapping("/api/v1/pipeline-runs")
@RequiredArgsConstructor
@Tag(name = "Pipeline runs (manual import)")
public class PipelineRunHitlController {

    private final PipelineRunImportService pipelineRunImportService;

    @PostMapping
    @Operation(summary = "Запустить импорт артефакта",
            description = "Создаёт запуск пайплайна для типа артефакта из тела запроса и запускает цепочку стадий. "
                    + "Структура payload зависит от artifactType: usecase — projectCode/name/plantUml, "
                    + "e2e-plantuml — name и ровно одно из plantUml/docId.")
    public ResponseEntity<CreatePipelineRunResponse> createPipelineRun(
            @RequestBody CreatePipelineRunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(pipelineRunImportService.createImportRun(request));
    }
}
