package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;

import java.util.NoSuchElementException;
import java.util.UUID;

/** Manual trigger: POST /configurations/{id}/run — starts artifact-pipeline-process directly. */
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final ConfigurationRepository configurationRepository;
    private final PipelineRunService      pipelineRunService;

    public void run(Long configurationId) {
        Configuration config = configurationRepository.findById(configurationId)
                .orElseThrow(() -> new NoSuchElementException("Configuration not found: " + configurationId));

        String artifactUid = UUID.randomUUID().toString();
        pipelineRunService.startArtifactPipeline(
                config.getId(), config.getArtifactType(), artifactUid, UUID.randomUUID().toString(), null);
    }
}
