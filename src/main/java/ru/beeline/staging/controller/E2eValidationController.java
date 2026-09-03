/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.client.DocumentServiceClient;
import ru.beeline.staging.dto.e2e.E2eValidateRequest;
import ru.beeline.staging.dto.e2e.E2eValidationReport;
import ru.beeline.staging.dto.e2e.ValidationNotice;
import ru.beeline.staging.e2e.EngineResult;
import ru.beeline.staging.e2e.PlantUmlValidationEngine;
import ru.beeline.staging.exception.DocumentNotFoundException;
import ru.beeline.staging.exception.DocumentServiceUnavailableException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Synchronous PlantUML e2e validation (STG-01/STG-02): no pipeline/Camunda side effects, no
 * canonical-model writes — both methods just parse and report (STG-06: same text, same report).
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/v1/e2e", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class E2eValidationController {

    private static final int MAX_PLANTUML_LENGTH = 500_000; // ~500 KB (STG-08)
    private static final long VALIDATION_TIMEOUT_MS = 10_000;

    private final PlantUmlValidationEngine engine;
    private final DocumentServiceClient documentServiceClient;

    @PostMapping("/validate")
    public ResponseEntity<?> validateText(@RequestBody E2eValidateRequest request) {
        if (request.plantUml() == null || request.plantUml().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", "plantUml must not be empty"));
        }
        return validate(request.plantUml());
    }

    @PostMapping("/validate/{docId}")
    public ResponseEntity<?> validateByDocId(@PathVariable Long docId) {
        String content;
        try {
            content = documentServiceClient.fetchContent(docId);
        } catch (DocumentNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("errorMessage", e.getMessage()));
        } catch (DocumentServiceUnavailableException e) {
            log.warn("document-service unavailable while fetching docId={}: {}", docId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorMessage", "document-service is unavailable, try again later"));
        }
        return validate(content);
    }

    private ResponseEntity<?> validate(String plantUmlText) {
        if (plantUmlText.length() > MAX_PLANTUML_LENGTH) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("errorMessage",
                    "PlantUML text exceeds the maximum allowed size of " + MAX_PLANTUML_LENGTH + " characters"));
        }
        try {
            EngineResult result = CompletableFuture.supplyAsync(() -> engine.validate(plantUmlText))
                    .orTimeout(VALIDATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .join();
            return ResponseEntity.ok(toReport(result));
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                        .body(Map.of("errorMessage", "Validation timed out"));
            }
            log.error("e2e validation failed", e.getCause());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorMessage", "Validation failed: " + e.getCause().getMessage()));
        }
    }

    private E2eValidationReport toReport(EngineResult result) {
        return new E2eValidationReport(
                result.valid(),
                result.recognizedParticipants(),
                result.unrecognizedParticipants(),
                result.recognizedCalls(),
                result.unrecognizedCalls(),
                result.findings().stream().map(ValidationNotice::from).toList());
    }
}
