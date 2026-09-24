/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.pipelinerun.ApplyPipelineRunResponse;
import ru.beeline.staging.dto.pipelinerun.CancelPipelineRunResponse;
import ru.beeline.staging.dto.pipelinerun.DeclinePipelineRunResponse;
import ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsRequest;
import ru.beeline.staging.dto.pipelinerun.PipelineRunDecisionsResponse;
import ru.beeline.staging.dto.usecase.ImportDecision;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.exception.PipelineRunUnresolvedPartsException;
import ru.beeline.staging.pipeline.manual.ManualOperations;
import ru.beeline.staging.repository.ImportDecisionRepository;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineHitlService {

    private static final String CANCELLED_STATUS = "cancelled";
    private static final String REVIEWING_STATUS = "reviewing";
    private static final String APPLYING_STATUS = "applying";
    private static final String DECLINED_STATUS = "completed_without_publish";
    private static final String DEFAULT_CANCEL_REASON = "Отменено пользователем";
    private static final Set<String> REVIEW_STATUSES = Set.of("awaiting_review", REVIEWING_STATUS);

    private final PipelineRunRepository pipelineRunRepository;
    private final ImportDecisionRepository importDecisionRepository;
    private final PipelineExecutionService pipelineExecutionService;
    private final MeterRegistry meterRegistry;
    private final ManualOperations manualOperations;

    public CancelPipelineRunResponse cancel(Long runId, String reason) {
        PipelineRun run = requireRun(runId);

        String cancelReason = reason == null || reason.isBlank() ? DEFAULT_CANCEL_REASON : reason.trim();
        if (pipelineRunRepository.markCancelled(runId, cancelReason) == 0) {
            throw conflict(runId, run, "нельзя отменить");
        }

        meterRegistry.counter("staging_pipeline_runs_total",
                "artifact_type", run.getArtifactType(), "status", CANCELLED_STATUS).increment();
        log.info("Cancelled run {} by user request: previousStatus={} reason={}", runId, run.getStatus(), cancelReason);
        return new CancelPipelineRunResponse(runId, CANCELLED_STATUS);
    }

    @Transactional
    public PipelineRunDecisionsResponse decide(Long runId, PipelineRunDecisionsRequest request) {
        PipelineRun run = requireRun(runId);
        List<PipelineRunDecisionsRequest.Decision> decisions = request == null || request.getDecisions() == null
                ? List.of() : request.getDecisions();
        if (decisions.isEmpty()) {
            throw new PipelineRunBadRequestException("Поле decisions обязательно и не должно быть пустым");
        }
        requireReviewStatus(run, "принимать решения");
        decisions.forEach(this::validateDecision);
        requireDistinctParts(decisions);

        Set<String> decidableParts = decidablePartsOf(run);
        for (PipelineRunDecisionsRequest.Decision decision : decisions) {
            if (!decidableParts.contains(decision.getPartId().trim())) {
                throw new PipelineRunNotFoundException(
                        "Часть не найдена в контексте паузы: runId=" + runId + " partId=" + decision.getPartId());
            }
        }

        for (PipelineRunDecisionsRequest.Decision decision : decisions) {
            String partId = decision.getPartId().trim();
            String targetJson = decision.getTarget() == null ? null : decision.getTarget().toString();
            String connectionJson = decision.getConnectionOperation() == null
                    || ImportDecision.PLANNED.equals(decision.getType())
                    ? null : decision.getConnectionOperation().toString();
            importDecisionRepository.upsert(runId, partId, decision.getType(), targetJson, connectionJson);
            manualOperations.applyDecision(run.getArtifactType(), runId,
                    new ImportDecision(null, runId, partId, decision.getType(), targetJson, connectionJson));
        }
        if (pipelineRunRepository.markReviewing(runId) == 0) {
            throw conflict(runId, run, "нельзя принимать решения");
        }

        int remaining = unresolvedParts(run).size();
        log.info("Accepted {} decision(s) for run {}: remaining={}", decisions.size(), runId, remaining);
        return new PipelineRunDecisionsResponse(runId, REVIEWING_STATUS, decisions.size(), remaining);
    }

    public DeclinePipelineRunResponse decline(Long runId) {
        PipelineRun run = requireRun(runId);
        requireReviewStatus(run, "отказаться от публикации");

        if (pipelineRunRepository.markDeclined(runId) == 0) {
            throw conflict(runId, run, "нельзя отказаться от публикации");
        }
        log.info("Run {} declined by user: публикация пропущена", runId);
        return new DeclinePipelineRunResponse(runId, DECLINED_STATUS);
    }

    public ApplyPipelineRunResponse apply(Long runId, String comment) {
        PipelineRun run = requireRun(runId);
        requireReviewStatus(run, "применить");

        List<String> unresolved = unresolvedParts(run);
        if (!unresolved.isEmpty()) {
            throw new PipelineRunUnresolvedPartsException(
                    "Есть несмаппированные части без решений: runId=" + runId, unresolved);
        }
        if (pipelineRunRepository.markApplying(runId) == 0) {
            throw conflict(runId, run, "нельзя применить");
        }

        log.info("Applying run {} by user request: comment={}", runId, comment);
        pipelineExecutionService.submitArtifactChain(runId, run.getArtifactType(), run.getArtifactType());
        return new ApplyPipelineRunResponse(runId, APPLYING_STATUS,
                "/api/v1/pipeline-runs/" + runId + "/status?waitFor=terminal");
    }

    private PipelineRun requireRun(Long runId) {
        if (runId == null || runId <= 0) {
            throw new PipelineRunBadRequestException("runId должен быть положительным числом");
        }
        return pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new PipelineRunNotFoundException("Запуск не найден: runId=" + runId));
    }

    private void requireReviewStatus(PipelineRun run, String action) {
        if (!REVIEW_STATUSES.contains(run.getStatus())) {
            throw new PipelineRunConflictException(
                    "Запуск в статусе " + run.getStatus() + " — " + action + " нельзя: runId=" + run.getId());
        }
    }

    private void validateDecision(PipelineRunDecisionsRequest.Decision decision) {
        if (decision == null || decision.getPartId() == null || decision.getPartId().isBlank()) {
            throw new PipelineRunBadRequestException("Поле decisions[].partId обязательно");
        }
        if (!hasNumber(decision.getTarget(), "stepVersionId")) {
            throw new PipelineRunBadRequestException("Поле decisions[].target.stepVersionId обязательно: partId="
                    + decision.getPartId());
        }
        if (ImportDecision.MAP_EXISTING.equals(decision.getType())) {
            if (!hasNumber(decision.getConnectionOperation(), "id")) {
                throw new PipelineRunBadRequestException("Для map_existing требуется connectionOperation.id: partId="
                        + decision.getPartId());
            }
        } else if (ImportDecision.PLANNED.equals(decision.getType())) {
            if (hasNumber(decision.getConnectionOperation(), "id")) {
                throw new PipelineRunBadRequestException("Для planned архитектурная операция не передаётся: partId="
                        + decision.getPartId() + " — используйте map_existing");
            }
        } else {
            throw new PipelineRunBadRequestException("Недопустимый тип решения " + decision.getType()
                    + ": допустимы map_existing, planned");
        }
    }

    private void requireDistinctParts(List<PipelineRunDecisionsRequest.Decision> decisions) {
        Set<String> seen = new HashSet<>();
        for (PipelineRunDecisionsRequest.Decision decision : decisions) {
            if (!seen.add(decision.getPartId().trim())) {
                throw new PipelineRunBadRequestException(
                        "Повторное решение по одной части в запросе: partId=" + decision.getPartId().trim());
            }
        }
    }

    private Set<String> decidablePartsOf(PipelineRun run) {
        Set<String> parts = new LinkedHashSet<>(unmappedParts(run));
        parts.addAll(decidedParts(run.getId()));
        if (parts.isEmpty()) {
            throw new PipelineRunConflictException("У запуска нет контекста паузы: runId=" + run.getId());
        }
        return parts;
    }

    private List<String> unmappedParts(PipelineRun run) {
        return manualOperations.unmappedParts(run.getArtifactType(), run.getId());
    }

    private Set<String> decidedParts(Long runId) {
        return importDecisionRepository.findByRunId(runId).stream()
                .map(ImportDecision::partId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> unresolvedParts(PipelineRun run) {
        Set<String> decided = decidedParts(run.getId());
        return unmappedParts(run).stream().filter(partId -> !decided.contains(partId)).toList();
    }

    private PipelineRunConflictException conflict(Long runId, PipelineRun run, String action) {
        String status = pipelineRunRepository.findById(runId).map(PipelineRun::getStatus).orElse(run.getStatus());
        return new PipelineRunConflictException("Запуск в статусе " + status + " — " + action + ": runId=" + runId);
    }

    private static boolean hasNumber(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && node.get(field).isNumber();
    }
}
