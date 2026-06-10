package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import ru.beeline.staging.config.RabbitConfig;
import ru.beeline.staging.consumer.dto.StagingEvent;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.repository.ConfigurationRepository;

import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final ConfigurationRepository configurationRepository;
    private final RabbitTemplate rabbitTemplate;

    public void run(Long configurationId) {
        Configuration config = configurationRepository.findById(configurationId)
                .orElseThrow(() -> new NoSuchElementException("Configuration not found: " + configurationId));

        StagingEvent event = new StagingEvent();
        event.setArtifactType(config.getArtifactType());
        event.setArtifactUid(UUID.randomUUID().toString());
        event.setSourceId(config.getSource());

        rabbitTemplate.convertAndSend(RabbitConfig.STAGING_EVENTS_QUEUE, event);
        log.info("Published staging event: configId={}, type={}, uid={}",
                configurationId, event.getArtifactType(), event.getArtifactUid());
    }
}
