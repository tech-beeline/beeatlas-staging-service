/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.beeline.staging.dto.usecase.UseCaseDraft;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunConflictException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.exception.PipelineRunUnresolvedPartsException;
import ru.beeline.staging.repository.ImportDecisionRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;

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
    private final ObjectMapper objectMapper;
    private final UseCaseLandscapeRepository landscapeRepository;
    private final RunBranchResolver runBranchResolver;

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
        decisions.forEach(this::validateDecision);
        requireDistinctParts(decisions);
        requireExistingTargets(runId, decisions);
        requireReviewStatus(run, "принимать решения");

        Set<String> unmappedParts = unmappedPartsOf(run);
        for (PipelineRunDecisionsRequest.Decision decision : decisions) {
            if (!unmappedParts.contains(decision.getPartId().trim())) {
                throw new PipelineRunNotFoundException(
                        "Часть не найдена в контексте паузы: runId=" + runId + " partId=" + decision.getPartId());
            }
        }

        for (PipelineRunDecisionsRequest.Decision decision : decisions) {
            boolean mapExisting = ImportDecision.MAP_EXISTING.equals(decision.getType());
            importDecisionRepository.upsert(runId, decision.getPartId().trim(), decision.getType(),
                    mapExisting ? decision.getTarget().toString() : null,
                    mapExisting ? null : decision.getNewRequest().toString());
        }
        if (pipelineRunRepository.markReviewing(runId) == 0) {
            throw conflict(runId, run, "нельзя принимать решения");
        }

        int remaining = unresolvedParts(runId, unmappedParts).size();
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

        List<String> unresolved = unresolvedParts(runId, unmappedPartsOrEmpty(run));
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
        if (ImportDecision.MAP_EXISTING.equals(decision.getType())) {
            if (!hasText(decision.getTarget(), "containerCode") || !hasText(decision.getTarget(), "interfaceCode")) {
                throw new PipelineRunBadRequestException("Для map_existing требуются target.containerCode и "
                        + "target.interfaceCode: partId=" + decision.getPartId());
            }
        } else if (ImportDecision.CREATE_NEW.equals(decision.getType())) {
            JsonNode request = decision.getNewRequest();
            if (!hasText(request, "productCode") || !hasText(request, "containerName")
                    || !hasText(request, "interfaceName")) {
                throw new PipelineRunBadRequestException("Для create_new требуются newRequest.productCode, "
                        + "containerName и interfaceName: partId=" + decision.getPartId());
            }
        } else {
            throw new PipelineRunBadRequestException("Недопустимый тип решения " + decision.getType()
                    + ": допустимы map_existing, create_new");
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

    private void requireExistingTargets(Long runId, List<PipelineRunDecisionsRequest.Decision> decisions) {
        String branch = runBranchResolver.resolve(runId);
        for (PipelineRunDecisionsRequest.Decision decision : decisions) {
            if (!ImportDecision.MAP_EXISTING.equals(decision.getType())) {
                continue;
            }
            String containerCode = decision.getTarget().get("containerCode").asText().trim();
            String interfaceCode = decision.getTarget().get("interfaceCode").asText().trim();
            if (landscapeRepository.findInterface(interfaceCode, containerCode, branch).isEmpty()) {
                throw new PipelineRunBadRequestException("Цель map_existing не найдена в ландшафте: containerCode="
                        + containerCode + " interfaceCode=" + interfaceCode + " partId=" + decision.getPartId());
            }
        }
    }

    private Set<String> unmappedPartsOf(PipelineRun run) {
        if (!hasDraft(run)) {
            throw new PipelineRunConflictException("У запуска нет контекста паузы: runId=" + run.getId());
        }
        return parseUnmappedParts(run);
    }

    private Set<String> unmappedPartsOrEmpty(PipelineRun run) {
        return hasDraft(run) ? parseUnmappedParts(run) : Set.of();
    }

    private boolean hasDraft(PipelineRun run) {
        return run.getDraftJson() != null && !run.getDraftJson().isBlank();
    }

    private Set<String> parseUnmappedParts(PipelineRun run) {
        try {
            return objectMapper.readValue(run.getDraftJson(), UseCaseDraft.class).unmappedOrEmpty().stream()
                    .map(UseCaseDraft.UnmappedPart::partId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            throw new IllegalStateException("draft_json запуска " + run.getId() + " не читается", e);
        }
    }

    private List<String> unresolvedParts(Long runId, Set<String> unmappedParts) {
        Set<String> decided = importDecisionRepository.findByRunId(runId).stream()
                .map(ImportDecision::partId)
                .collect(Collectors.toSet());
        return unmappedParts.stream().filter(partId -> !decided.contains(partId)).toList();
    }

    private PipelineRunConflictException conflict(Long runId, PipelineRun run, String action) {
        String status = pipelineRunRepository.findById(runId).map(PipelineRun::getStatus).orElse(run.getStatus());
        return new PipelineRunConflictException("Запуск в статусе " + status + " — " + action + ": runId=" + runId);
    }

    private static boolean hasText(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && !node.get(field).asText().isBlank();
    }
}
