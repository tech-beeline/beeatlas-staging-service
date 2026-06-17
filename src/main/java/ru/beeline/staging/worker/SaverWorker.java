package ru.beeline.staging.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.CanonicalModelPublisher;
import ru.beeline.staging.pipeline.CanonicalModelSaverService;
import ru.beeline.staging.pipeline.CanonicalSnapshot;
import ru.beeline.staging.service.PipelineRunService;

import java.util.List;
import java.util.Map;

/**
 * Final pipeline stage: persists the CanonicalSnapshot produced by the Transformer stage
 * into the canonical model (our own representation), then notifies the CanonicalModelPublisher.
 * Creates an ArtifactBatch grouping all version rows from this run — the batch with
 * is_current=TRUE is the "эталон" that was last sent to cx-backend.
 */
@Component
@RequiredArgsConstructor
public class SaverWorker extends AbstractWorker {

    private final CanonicalModelSaverService canonicalModelSaverService;
    private final CanonicalModelPublisher    canonicalModelPublisher;
    private final PipelineRunService         pipelineRunService;
    private final ObjectMapper               objectMapper;

    @Override
    protected String topic() { return "saver"; }

    @Override
    protected String workerId() { return "staging-saver-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactType", "artifactUid", "rawDataRefId", "canonicalSnapshotJson");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        long rawDataRefId = ((Number) task.getVariables().get("rawDataRefId")).longValue();
        String snapshotJson = (String) task.getVariables().get("canonicalSnapshotJson");
        Long runId = task.getVariables().get("pipelineRunId") instanceof Number n ? n.longValue() : null;

        log.info("stage=saver, type={}, uid={}", type, uid);

        if (snapshotJson == null || snapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to save", uid);
            return null;
        }

        CanonicalSnapshot snapshot = objectMapper.readValue(snapshotJson, CanonicalSnapshot.class);
        CanonicalModelSaverService.SaveResult result =
                canonicalModelSaverService.save(snapshot, rawDataRefId, runId, uid, type);

        log.info("Saved canonical model for uid={}: {}", uid, result);
        canonicalModelPublisher.publish(type, uid, snapshot, result);

        if (runId != null) {
            pipelineRunService.completeRun(runId);
        }

        return Map.of("batchId", result.getBatchId() != null ? result.getBatchId() : -1L);
    }
}
