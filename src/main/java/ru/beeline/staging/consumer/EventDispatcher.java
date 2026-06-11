package ru.beeline.staging.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ru.beeline.staging.config.RabbitConfig;
import ru.beeline.staging.consumer.dto.StagingEvent;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventDispatcher {

    private final RuntimeService runtimeService;

    @RabbitListener(queues = RabbitConfig.STAGING_EVENTS_QUEUE)
    public void handleEvent(StagingEvent event) {
        log.info("Received staging event: type={}, uid={}, configId={}, batchId={}",
                event.getArtifactType(), event.getArtifactUid(),
                event.getConfigurationId(), event.getBatchId());

        Map<String, Object> variables = new HashMap<>();
        variables.put("artifactType",    event.getArtifactType());
        variables.put("artifactUid",     event.getArtifactUid());
        variables.put("sourceId",        event.getSourceId());
        variables.put("configurationId", event.getConfigurationId());
        variables.put("batchId",         event.getBatchId());

        runtimeService.startProcessInstanceByMessage(
                "artifact.ready",
                event.getArtifactUid(),
                variables
        );

        log.info("Started artifact-pipeline-process for uid={}", event.getArtifactUid());
    }
}
