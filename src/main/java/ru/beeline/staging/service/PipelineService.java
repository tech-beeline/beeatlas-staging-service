package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import ru.beeline.staging.config.RabbitConfig;
import ru.beeline.staging.consumer.dto.StagingEvent;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final ConfigurationRepository configurationRepository;
    private final RabbitTemplate          rabbitTemplate;

    /** Manual trigger: POST /configurations/{id}/run */
    public void run(Long configurationId) {
        Configuration config = configurationRepository.findById(configurationId)
                .orElseThrow(() -> new NoSuchElementException("Configuration not found: " + configurationId));
        publishEvent(config, UUID.randomUUID().toString());
    }

    /** Publish a single event with a generated artifactUid (used for manual/test runs). */
    public void publishEvent(Configuration config, String batchId) {
        publishArtifactEvent(config, batchId, UUID.randomUUID().toString(), null);
    }

    /** Publish an event for a specific artifact discovered by a pre-adapter. */
    public void publishArtifactEvent(Configuration config, String batchId,
                                     String artifactUid, Map<String, Object> metadata) {
        StagingEvent event = new StagingEvent();
        event.setArtifactType(config.getArtifactType());
        event.setArtifactUid(artifactUid);
        event.setSourceId(String.valueOf(config.getSourceSystemId()));
        event.setConfigurationId(config.getId());
        event.setBatchId(batchId);
        event.setMetadata(metadata);

        rabbitTemplate.convertAndSend(RabbitConfig.STAGING_EVENTS_QUEUE, event);
        log.info("Published staging event: configId={}, type={}, uid={}",
                config.getId(), config.getArtifactType(), artifactUid);
    }
}
