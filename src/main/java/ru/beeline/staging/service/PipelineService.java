/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.worker.PipelineTickScheduler;

import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class PipelineService {

    private final ConfigurationRepository configurationRepository;
    private final PipelineTickScheduler   pipelineTickScheduler;

    public void run(Long configurationId) {
        Configuration config = configurationRepository.findById(configurationId)
                .orElseThrow(() -> new NoSuchElementException("Configuration not found: " + configurationId));
        pipelineTickScheduler.startScan(config);
    }
}
