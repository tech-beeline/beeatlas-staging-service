package ru.beeline.staging.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.pipeline.CanonicalModelPublisher;
import ru.beeline.staging.pipeline.CanonicalModelSaverService;
import ru.beeline.staging.pipeline.CanonicalSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Final pipeline stage: persists the CanonicalSnapshot produced by the Transformer stage
 * into the canonical model (our own representation), then notifies the (currently no-op)
 * CanonicalModelPublisher extension point. Source-agnostic — no per-type dispatch needed
 * here since CanonicalSnapshot is already a normalized, source-independent shape.
 */
@Component
@RequiredArgsConstructor
public class SaverWorker extends AbstractWorker {

    private final CanonicalModelSaverService canonicalModelSaverService;
    private final CanonicalModelPublisher    canonicalModelPublisher;
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

        log.info("stage=saver, type={}, uid={}", type, uid);

        if (snapshotJson == null || snapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to save", uid);
            return null;
        }

        CanonicalSnapshot snapshot = objectMapper.readValue(snapshotJson, CanonicalSnapshot.class);
        CanonicalModelSaverService.SaveResult result = canonicalModelSaverService.save(snapshot, rawDataRefId);

        log.info("Saved canonical model for uid={}: {}", uid, result);
        canonicalModelPublisher.publish(type, uid, snapshot, result);

        return null;
    }
}
