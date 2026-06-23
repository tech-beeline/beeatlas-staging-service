package ru.beeline.staging.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.pipeline.CanonicalModelSaverService;
import ru.beeline.staging.pipeline.CanonicalSnapshot;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.service.PipelineRunService;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Final pipeline stage: persists the CanonicalSnapshot produced by the Transformer stage
 * into the canonical model (our own representation). Creates an ArtifactBatch grouping all
 * version rows from this run — the batch with is_current=TRUE is the эталон ("наше представление").
 */
@Component
@RequiredArgsConstructor
public class SaverWorker extends AbstractWorker {

    private final CanonicalModelSaverService canonicalModelSaverService;
    private final PipelineRunService         pipelineRunService;
    private final RawDataRefRepository       rawDataRefRepository;
    private final ObjectMapper               objectMapper;

    @Override
    protected String topic() { return "saver"; }

    @Override
    protected String workerId() { return "staging-saver-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactType", "artifactUid", "rawDataRefId");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        long rawDataRefId = ((Number) task.getVariables().get("rawDataRefId")).longValue();
        Long runId = task.getVariables().get("pipelineRunId") instanceof Number n ? n.longValue() : null;

        log.info("stage=saver, type={}, uid={}", type, uid);

        RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));
        String snapshotJson = ref.getCanonicalSnapshotJson();

        if (snapshotJson == null || snapshotJson.isBlank()) {
            log.warn("No canonicalSnapshotJson present for uid={} — nothing to save", uid);
            return null;
        }

        CanonicalSnapshot snapshot = objectMapper.readValue(snapshotJson, CanonicalSnapshot.class);
        CanonicalModelSaverService.SaveResult result =
                canonicalModelSaverService.save(snapshot, rawDataRefId, runId, uid, type);

        log.info("Saved canonical model for uid={}: {}", uid, result);

        // canonical_snapshot_json only existed to ferry Transformer's output to this stage
        // (instead of an oversized Camunda process variable) — now that it's persisted into
        // the canonical tables, drop it so raw_data_refs doesn't keep growing indefinitely.
        ref.setCanonicalSnapshotJson(null);
        rawDataRefRepository.save(ref);

        if (runId != null) {
            pipelineRunService.completeRun(runId);
        }

        return Map.of("batchId", result.getBatchId() != null ? result.getBatchId() : -1L);
    }
}
