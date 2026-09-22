/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ru.beeline.staging.exception.ActivePipelineRunException;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunForbiddenException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.exception.PipelineRunUnresolvedPartsException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = PipelineRunHitlController.class)
public class PipelineRunImportExceptionHandler {

    @ExceptionHandler(PipelineRunBadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(PipelineRunBadRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("errorMessage", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("errorMessage", "Неверный формат параметра " + e.getName() + ": " + e.getValue()));
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

    @ExceptionHandler(PipelineRunUnresolvedPartsException.class)
    public ResponseEntity<Map<String, Object>> handleUnresolvedParts(PipelineRunUnresolvedPartsException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("unresolvedParts", e.getUnresolvedParts());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(PipelineRunForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(PipelineRunForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("errorMessage", e.getMessage()));
    }

    @ExceptionHandler(PipelineRunConflictException.class)
    public ResponseEntity<Map<String, Object>> handleRunConflict(PipelineRunConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
