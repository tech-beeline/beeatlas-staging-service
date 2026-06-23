package ru.beeline.staging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ru.beeline.staging.config.RabbitConfig;
import ru.beeline.staging.consumer.dto.StagingEvent;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.service.PipelineRunService;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventDispatcher {

    private final RuntimeService   runtimeService;
    private final ObjectMapper     objectMapper;
    private final PipelineRunService pipelineRunService;

    @RabbitListener(queues = RabbitConfig.STAGING_EVENTS_QUEUE)
    public void handleEvent(StagingEvent event) {
        log.info("Received staging event: type={}, uid={}, configId={}, batchId={}",
                event.getArtifactType(), event.getArtifactUid(),
                event.getConfigurationId(), event.getBatchId());

        // Create our own tracking record before handing off to Camunda
        PipelineRun run = pipelineRunService.createRun(
                event.getArtifactUid(),
                event.getArtifactType(),
                event.getConfigurationId()
        );

        Map<String, Object> variables = new HashMap<>();
        variables.put("artifactType",    event.getArtifactType());
        variables.put("artifactUid",     event.getArtifactUid());
        variables.put("sourceId",        event.getSourceId());
        variables.put("configurationId", event.getConfigurationId());
        variables.put("batchId",         event.getBatchId());
        variables.put("pipelineRunId",   run.getId());   // propagated through all stages

        if (event.getMetadata() != null) {
            try {
                variables.put("metadataJson", objectMapper.writeValueAsString(event.getMetadata()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize metadata for uid={}", event.getArtifactUid(), e);
            }
        }

        ProcessInstance pi = runtimeService.startProcessInstanceByMessage(
                "artifact.ready",
                event.getArtifactUid(),
                variables
        );

        pipelineRunService.bindCamundaPid(run.getId(), pi.getId());
        log.info("Started artifact-pipeline-process for uid={}, pipelineRunId={}, camundaPid={}",
                event.getArtifactUid(), run.getId(), pi.getId());
    }
}
