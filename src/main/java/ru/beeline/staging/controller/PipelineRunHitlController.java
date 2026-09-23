/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.dto.pipelinerun.ApplyPipelineRunRequest;
import ru.beeline.staging.dto.pipelinerun.ApplyPipelineRunResponse;
import ru.beeline.staging.dto.pipelinerun.CancelPipelineRunRequest;
import ru.beeline.staging.dto.pipelinerun.CancelPipelineRunResponse;
import ru.beeline.staging.dto.pipelinerun.CreatePipelineRunRequest;
import ru.beeline.staging.dto.pipelinerun.DeclinePipelineRunResponse;
import ru.beeline.staging.dto.pipelinerun.CreatePipelineRunResponse;
import ru.beeline.staging.dto.pipelinerun.PipelineRunBadRequestResponse;
import ru.beeline.staging.dto.pipelinerun.PipelineRunErrorResponse;
import ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsRequest;
import ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsResponse;
import ru.beeline.staging.service.PipelineHitlService;
import ru.beeline.staging.service.PipelineRunAccessGuard;
import ru.beeline.staging.service.PipelineRunImportService;
import ru.beeline.staging.utils.Constant;

@RestController
@RequestMapping("/api/v1/pipeline-runs")
@RequiredArgsConstructor
@Tag(name = "Pipeline runs (manual import)")
public class PipelineRunHitlController {

    private final PipelineRunImportService pipelineRunImportService;
    private final PipelineHitlService pipelineHitlService;
    private final PipelineRunAccessGuard pipelineRunAccessGuard;

    @PostMapping
    @Operation(summary = "Запустить импорт артефакта",
            description = "Создаёт запуск пайплайна для типа артефакта из тела запроса и запускает цепочку стадий. "
                    + "Структура payload зависит от artifactType: usecase — projectCode/name/plantUml, "
                    + "e2e-plantuml — name и ровно одно из plantUml/docId.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Запуск создан",
                    content = @Content(schema = @Schema(implementation = CreatePipelineRunResponse.class))),
            @ApiResponse(responseCode = "400", description = "Некорректное тело запроса",
                    content = @Content(schema = @Schema(implementation = PipelineRunBadRequestResponse.class))),
            @ApiResponse(responseCode = "409", description = "Для артефакта уже есть активный запуск "
                    + "(в теле дополнительно activeRunId)",
                    content = @Content(schema = @Schema(implementation = PipelineRunErrorResponse.class)))
    })
    public ResponseEntity<CreatePipelineRunResponse> createPipelineRun(
            @RequestBody CreatePipelineRunRequest request,
            @RequestHeader(value = Constant.USER_ID_HEADER, required = false) Integer userId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(pipelineRunImportService.createImportRun(request, userId));
    }

    @PostMapping("/{runId}/decisions")
    @Operation(summary = "Принять решения по несмаппированным частям",
            description = "Записывает решения по несопоставленным частям контекста паузы и применяет их "
                    + "к канонической модели (фаза 2, ADR-029). target — шаг, записанный saver-ом "
                    + "(stepVersionId, type, name, productAlias, interfaceCode); connectionOperation — операция "
                    + "из fdm-products (id, operationType, operationName, interfaceCode, containerCode, "
                    + "productAlias). type=map_existing — операция указана (call_status=architect_specified), "
                    + "type=planned — архитектурной операции нет (call_status=planned). Запуск переводится "
                    + "в reviewing; повторное решение по partId заменяет предыдущее. 400 — некорректное решение, "
                    + "404 — запуск или часть не найдены, 409 — запуск не в awaiting_review/reviewing либо "
                    + "контекст паузы устарел (stepVersionId или эхо не совпали).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Решения приняты",
                    content = @Content(schema = @Schema(implementation = PipelineRunDecisionsResponse.class))),
            @ApiResponse(responseCode = "400", description = "Некорректное решение, цель map_existing не найдена "
                    + "в ландшафте, дубль partId в запросе или неверный формат runId",
                    content = @Content(schema = @Schema(implementation = PipelineRunBadRequestResponse.class))),
            @ApiResponse(responseCode = "404", description = "Запуск или часть не найдены",
                    content = @Content(schema = @Schema(implementation = PipelineRunErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Запуск не в awaiting_review/reviewing",
                    content = @Content(schema = @Schema(implementation = PipelineRunErrorResponse.class)))
    })
    public ResponseEntity<PipelineRunDecisionsResponse> decide(
            @PathVariable Long runId,
            @RequestHeader(value = Constant.USER_ID_HEADER, required = false) Integer userId,
            @RequestBody(required = false) PipelineRunDecisionsRequest request) {
        pipelineRunAccessGuard.requireDecisionRights(runId, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(pipelineHitlService.decide(runId, request));
    }

    @PostMapping("/{runId}/apply")
    @Operation(summary = "Применить UseCase",
            description = "Переводит запуск из паузы в applying и продолжает цепочку стадией saver: UseCase, шаги "
                    + "и плановые требования пишутся в каноническую модель staging (без публикации в fdm-products). "
                    + "404 — запуск не найден, 409 — есть части без решений (unresolvedParts) или запуск "
                    + "не в awaiting_review/reviewing.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Применение запущено",
                    content = @Content(schema = @Schema(implementation = ApplyPipelineRunResponse.class))),
            @ApiResponse(responseCode = "400", description = "Неверный формат runId",
                    content = @Content(schema = @Schema(implementation = PipelineRunBadRequestResponse.class))),
            @ApiResponse(responseCode = "404", description = "Запуск не найден",
                    content = @Content(schema = @Schema(implementation = PipelineRunErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Есть части без решений (в теле дополнительно "
                    + "unresolvedParts) или запуск не в awaiting_review/reviewing",
                    content = @Content(schema = @Schema(implementation = PipelineRunErrorResponse.class)))
    })
    public ResponseEntity<ApplyPipelineRunResponse> apply(
            @PathVariable Long runId,
            @RequestHeader(value = Constant.USER_ID_HEADER, required = false) Integer userId,
            @RequestBody(required = false) ApplyPipelineRunRequest request) {
        pipelineRunAccessGuard.requireDecisionRights(runId, userId);
        String comment = request == null ? null : request.getComment();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(pipelineHitlService.apply(runId, comment));
    }

    @PostMapping("/{runId}/cancel")
    @Operation(summary = "Отменить запуск",
            description = "Переводит запуск в cancelled и останавливает цепочку стадий; в каноническую модель "
                    + "ничего не пишется. Причина из тела сохраняется в failure_reason. 404 — запуск не найден, "
                    + "409 — запуск уже в терминальном статусе (completed/failed/cancelled) "
                    + "или в состоянии применения (saving/publishing/applying).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Запуск отменён",
                    content = @Content(schema = @Schema(implementation = CancelPipelineRunResponse.class))),
            @ApiResponse(responseCode = "400", description = "Неверный формат runId или runId не положительный",
                    content = @Content(schema = @Schema(implementation = PipelineRunBadRequestResponse.class))),
            @ApiResponse(responseCode = "404", description = "Запуск не найден",
                    content = @Content(schema = @Schema(implementation = PipelineRunErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Запуск в терминальном статусе или в состоянии применения",
                    content = @Content(schema = @Schema(implementation = PipelineRunErrorResponse.class)))
    })
    public ResponseEntity<CancelPipelineRunResponse> cancelPipelineRun(
            @PathVariable Long runId,
            @RequestHeader(value = Constant.USER_ID_HEADER, required = false) Integer userId,
            @RequestBody(required = false) CancelPipelineRunRequest request) {
        pipelineRunAccessGuard.requireDecisionRights(runId, userId);
        String reason = request == null ? null : request.getReason();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(pipelineHitlService.cancel(runId, reason));
    }

    @PostMapping("/{runId}/decline")
    @Operation(summary = "Отказаться от публикации",
            description = "Завершает запуск без публикации во внешние сервисы: стадия publisher не вызывается, "
                    + "статус становится completed_without_publish. 403 — решение принимает не автор запуска, "
                    + "404 — запуск не найден, 409 — запуск не в awaiting_review/reviewing.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Публикация отклонена",
                    content = @Content(schema = @Schema(implementation = DeclinePipelineRunResponse.class))),
            @ApiResponse(responseCode = "404", description = "Запуск не найден",
                    content = @Content(schema = @Schema(implementation = PipelineRunErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Запуск не в awaiting_review/reviewing",
                    content = @Content(schema = @Schema(implementation = PipelineRunErrorResponse.class)))
    })
    public ResponseEntity<DeclinePipelineRunResponse> decline(
            @PathVariable Long runId,
            @RequestHeader(value = Constant.USER_ID_HEADER, required = false) Integer userId) {
        pipelineRunAccessGuard.requireDecisionRights(runId, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pipelineHitlService.decline(runId));
    }
}
