/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
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
import ru.beeline.staging.dto.e2e.E2eValidationReport;
import ru.beeline.staging.dto.e2e.ValidationNotice;
import ru.beeline.staging.e2e.E2ePlantUmlValidation;
import ru.beeline.staging.e2e.EngineResult;
import ru.beeline.staging.exception.DocumentAccessDeniedException;
import ru.beeline.staging.exception.DocumentNotFoundException;
import ru.beeline.staging.exception.DocumentServiceUnavailableException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequestMapping(value = "/api/v1/e2e", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class E2eValidationController {

    private static final int MAX_PLANT_UML_BYTES = 512 * 1024;
    private static final long VALIDATION_TIMEOUT_MS = 10_000;

    private final E2ePlantUmlValidation validation;
    private final DocumentServiceClient documentServiceClient;

    @PostMapping("/validate")
    public ResponseEntity<?> validateText(@RequestBody JsonNode request) {
        JsonNode plantUml = request.get("plantUml");
        if (plantUml == null || plantUml.isNull() || plantUml.asText().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", "plantUml must not be empty"));
        }
        return validate(plantUml.asText(), request);
    }

    @PostMapping("/validate/{docId}")
    public ResponseEntity<?> validateByDocId(@PathVariable Long docId,
                                             @RequestBody(required = false) JsonNode metadata) {
        String content;
        try {
            content = documentServiceClient.fetchContent(docId);
        } catch (DocumentNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("errorMessage", e.getMessage()));
        } catch (DocumentAccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("errorMessage", e.getMessage()));
        } catch (DocumentServiceUnavailableException e) {
            log.warn("document-service unavailable while fetching docId={}: {}", docId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorMessage", "document-service is unavailable, try again later"));
        }
        return validate(content, metadata == null ? MissingNode.getInstance() : metadata);
    }

    private ResponseEntity<?> validate(String plantUmlText, JsonNode metadata) {
        if (plantUmlText.getBytes(StandardCharsets.UTF_8).length > MAX_PLANT_UML_BYTES) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("errorMessage",
                    "PlantUML text exceeds the maximum allowed size of " + MAX_PLANT_UML_BYTES + " bytes"));
        }
        try {
            EngineResult result = CompletableFuture.supplyAsync(() -> validation.validate(plantUmlText, metadata))
                    .orTimeout(VALIDATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .join();
            return ResponseEntity.ok(toReport(result, plantUmlText));
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                        .body(Map.of("errorMessage", "Validation timed out"));
            }
            log.error("e2e validation failed", e.getCause());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorMessage", "Validation failed due to an internal error, try again later"));
        }
    }

    private E2eValidationReport toReport(EngineResult result, String sourceText) {
        return new E2eValidationReport(
                result.valid(),
                result.recognizedParticipants(),
                result.unrecognizedParticipants(),
                result.recognizedCalls(),
                result.unrecognizedCalls(),
                result.findings().stream().map(finding -> ValidationNotice.from(finding, sourceText)).toList());
    }
}
