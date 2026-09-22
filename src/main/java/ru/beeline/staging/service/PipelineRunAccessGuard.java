/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.beeline.staging.client.AuthUserClient;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.exception.PipelineRunForbiddenException;
import ru.beeline.staging.repository.PipelineRunRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineRunAccessGuard {

    private final PipelineRunRepository pipelineRunRepository;
    private final AuthUserClient authUserClient;

    public void requireDecisionRights(Long runId, Integer userId) {
        Integer author = pipelineRunRepository.findById(runId)
                .map(PipelineRun::getCreatedByUserId)
                .orElse(null);
        if (author == null || author.equals(userId)) {
            return;
        }
        if (authUserClient.isAdministrator(userId)) {
            log.info("Решение по прогону {} принимает администратор userId={}, автор — {}", runId, userId, author);
            return;
        }
        throw new PipelineRunForbiddenException(
                "Решения по запуску может принимать только пользователь, который его запустил");
    }
}
