/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.repository.PipelineRunDetailsRepository;
import ru.beeline.staging.utils.Constant;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/user/pipeline-runs")
@RequiredArgsConstructor
@Tag(name = "User pipeline runs")
public class UserPipelineRunsController {

    private static final int DEFAULT_LIMIT = 50;

    private final PipelineRunDetailsRepository pipelineRunDetailsRepository;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "История запусков пайплайнов пользователя",
            description = "Запуски, связанные с пользователем из заголовка user-id, от новых к старым. "
                    + "Состав объекта совпадает с GET /api/v1/pipeline-runs/{runId}/details.")
    public ResponseEntity<?> listUserRuns(
            @RequestHeader(value = Constant.USER_ID_HEADER, required = false) String userIdHeader,
            @Parameter(description = "Фильтр по типу артефакта, без учёта регистра")
            @RequestParam(name = "artifact-type", required = false) String artifactType,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {

        if (userIdHeader == null || userIdHeader.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Запрос не содержит данных пользователя"));
        }
        Integer userId;
        try {
            userId = Integer.valueOf(userIdHeader.trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Запрос содержит не верный формат USER-ID"));
        }
        if (artifactType != null && artifactType.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Пустой значение параметра artifact-type"));
        }
        if (limit != null && limit < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Параметры limit и offset не могут быть отрицательными"));
        }
        if (offset < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Параметры limit и offset не могут быть отрицательными"));
        }

        return ResponseEntity.ok(pipelineRunDetailsRepository.findByCreatedByUserId(userId,
                artifactType != null ? artifactType.trim() : null,
                limit != null ? limit : DEFAULT_LIMIT, offset));
    }
}
