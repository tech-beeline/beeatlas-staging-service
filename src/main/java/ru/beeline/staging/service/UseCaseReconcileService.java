/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.dto.usecase.ReconcileResponse;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeOperation;
import ru.beeline.staging.repository.UseCaseReconcileRepository;
import ru.beeline.staging.repository.UseCaseReconcileRepository.Candidate;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UseCaseReconcileService {

    private static final String TYPE_OPERATION = "operation";
    private static final String STATUS_MATCHED = "matched";
    private static final String COMPLETED = "completed";

    private final UseCaseReconcileRepository reconcileRepository;
    private final UseCaseLandscapeRepository landscapeRepository;

    private record Match(Long operationVersionId) {
    }

    @Transactional
    public ReconcileResponse reconcile() {
        int matched = 0;
        int remainingRequired = 0;
        for (Candidate candidate : reconcileRepository.findCandidates()) {
            Optional<Match> match = find(candidate);
            if (match.isPresent()) {
                if (reconcileRepository.markMatched(candidate.id(), match.get().operationVersionId()) > 0) {
                    matched++;
                }
            } else if (STATUS_MATCHED.equals(candidate.status())) {
                log.warn("Reconcile: matched requirement {} ({} {}) is no longer in branch {} — "
                                + "keeping operationVersionId={}", candidate.id(), candidate.type(),
                        codeOf(candidate), candidate.branch(), candidate.operationVersionId());
            } else {
                remainingRequired++;
            }
        }
        log.info("Reconcile completed: matched={} remainingRequired={}", matched, remainingRequired);
        return new ReconcileResponse(null, COMPLETED, matched, remainingRequired);
    }

    private Optional<Match> find(Candidate candidate) {
        if (TYPE_OPERATION.equals(candidate.type())) {
            if (candidate.operationCode() == null) {
                return Optional.empty();
            }
            return landscapeRepository
                    .findOperationByCode(candidate.operationCode(), candidate.interfaceCode(), candidate.branch())
                    .map(LandscapeOperation::operationVersionId)
                    .map(Match::new);
        }
        if (candidate.interfaceCode() == null || candidate.containerCode() == null) {
            return Optional.empty();
        }
        return landscapeRepository
                .findInterface(candidate.interfaceCode(), candidate.containerCode(), candidate.branch())
                .map(found -> new Match(null));
    }

    private static String codeOf(Candidate candidate) {
        return TYPE_OPERATION.equals(candidate.type()) ? candidate.operationCode() : candidate.interfaceCode();
    }
}
