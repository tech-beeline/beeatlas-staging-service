package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.dto.notice.SaveResult;
import ru.beeline.staging.pipeline.saver.ArtifactSaver;
import ru.beeline.staging.repository.ArtifactBatchRepository;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import ru.beeline.staging.domain.ArtifactBatch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SaverWorker extends AbstractWorker {

    private final List<ArtifactSaver>      savers;
    private final ModuleResolver           moduleResolver;
    private final PipelineRunService       pipelineRunService;
    private final RawDataRefRepository     rawDataRefRepository;
    private final ArtifactBatchRepository  artifactBatchRepository;

    private Map<String, ArtifactSaver> registry;

    @PostConstruct
    void init() {
        registry = savers.stream().collect(Collectors.toMap(ArtifactSaver::moduleCode, s -> s));
        log.info("SaverWorker registry initialized for modules: {}", registry.keySet());
    }

    @Override
    protected String topic() { return "saver"; }

    @Override
    protected String workerId() { return "staging-saver-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactType", "artifactUid", "rawDataRefId", "configurationId", "pipelineRunId");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        long rawDataRefId = ((Number) task.getVariables().get("rawDataRefId")).longValue();
        Long runId = ((Number) task.getVariables().get("pipelineRunId")).longValue();

        Long stageLogId = pipelineRunService.startStage(runId, "saver", "rawDataRefId=" + rawDataRefId);
        try {
            if (pipelineRunService.isAlreadyCompleted(runId)) {
                log.info("Run {} already completed — skipping duplicate save for uid={}", runId, uid);
                pipelineRunService.completeStage(stageLogId, "skipped: already completed", null);
                return null;
            }

            if (isAlreadyFullyProcessed(uid, type, rawDataRefId)) {
                log.info("stage=saver, uid={} — content unchanged and previously completed (rawDataRefId={}), skipping save", uid, rawDataRefId);
                pipelineRunService.completeStage(stageLogId, "skipped: content unchanged", null);
                pipelineRunService.completeRun(runId);
                return Map.of("saved", false);
            }

            String moduleCode = moduleResolver.resolve(type, topic());
            ArtifactSaver saver = registry.get(moduleCode);
            if (saver == null) {
                throw new IllegalStateException("No ArtifactSaver registered for moduleCode=" + moduleCode);
            }

            log.info("stage=saver, module={}, uid={}", moduleCode, uid);

            RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                    .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

            SaveResult saverResult = saver.save(uid, type, rawDataRefId, runId, ref.getCanonicalSnapshotJson());
            // match-notices are saved inside the saver's own transaction; saverResult.notices() is empty

            Map<String, Object> output = new HashMap<>(saverResult.summary() != null ? saverResult.summary() : Map.of());
            output.put("saved", true);

            ref.setCanonicalSnapshotJson(null);
            rawDataRefRepository.save(ref);

            Object batchId = output.get("batchId");
            String outputSummary = batchId != null ? "batchId=" + batchId : "saved=true";
            pipelineRunService.completeStage(stageLogId, outputSummary, buildSummary(output));
            pipelineRunService.completeRun(runId);

            return output;
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, "saver", e.getMessage());
            throw e;
        }
    }

    // A batch existing for this exact content isn't enough on its own: since the canonical save and the
    // fdm-products publish now commit independently (see E2eCanonicalSnapshotSaver), a batch can exist
    // for a run that ultimately failed at the publish step. Only skip re-saving when that earlier run
    // actually completed — otherwise a retry would wrongly report "unchanged" and never retry publish.
    private boolean isAlreadyFullyProcessed(String uid, String type, long rawDataRefId) {
        Optional<ArtifactBatch> currentBatch = artifactBatchRepository.findByArtifactUidAndArtifactTypeAndCurrentTrue(uid, type);
        return currentBatch
                .filter(b -> Long.valueOf(rawDataRefId).equals(b.getRawDataRefId()))
                .map(ArtifactBatch::getRunId)
                .filter(pipelineRunService::isAlreadyCompleted)
                .isPresent();
    }
}
