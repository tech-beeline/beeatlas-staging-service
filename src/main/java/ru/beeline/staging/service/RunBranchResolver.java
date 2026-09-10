/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.repository.PipelineRunRepository;

@Service
@RequiredArgsConstructor
public class RunBranchResolver {

    public static final String DEFAULT_BRANCH = "main";

    private final PipelineRunRepository pipelineRunRepository;

    public String resolve(Long runId) {
        if (runId == null) {
            return DEFAULT_BRANCH;
        }
        return pipelineRunRepository.findById(runId)
                .map(PipelineRun::getBranch)
                .filter(branch -> !branch.isBlank())
                .orElse(DEFAULT_BRANCH);
    }
}
