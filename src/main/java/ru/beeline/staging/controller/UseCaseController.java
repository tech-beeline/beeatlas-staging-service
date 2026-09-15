/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.dto.usecase.ReconcileResponse;
import ru.beeline.staging.service.UseCaseReconcileService;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/usecases")
@RequiredArgsConstructor
@Tag(name = "UseCases")
public class UseCaseController {

    private final UseCaseReconcileService useCaseReconcileService;

    @PostMapping("/reconcile")
    @Operation(summary = "Сверить плановые элементы",
            description = "Ручная сверка required_operation_versions с загруженными архитектурами в ветке требования "
                    + "(при NULL — main): операция — по коду операции в рамках интерфейса, интерфейс — по кодам "
                    + "интерфейса и контейнера. При совпадении — matched + operation_version_id (in-place, "
                    + "идемпотентно). Содержимое UseCase не меняется.")
    public ResponseEntity<ReconcileResponse> reconcile() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(useCaseReconcileService.reconcile());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleFailure(RuntimeException e) {
        log.error("UseCase reconcile failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
