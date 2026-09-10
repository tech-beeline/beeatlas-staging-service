/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.beeline.staging.exception.ActivePipelineRunException;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = PipelineRunHitlController.class)
public class PipelineRunImportExceptionHandler {

    @ExceptionHandler(PipelineRunBadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(PipelineRunBadRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("errorMessage", e.getMessage()));
    }

    @ExceptionHandler(PipelineRunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(PipelineRunNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ActivePipelineRunException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ActivePipelineRunException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("activeRunId", e.getActiveRunId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
