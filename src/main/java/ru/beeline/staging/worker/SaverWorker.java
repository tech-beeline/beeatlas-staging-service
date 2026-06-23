package ru.beeline.staging.worker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.pipeline.saver.ArtifactSaver;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Final pipeline stage: resolves the ArtifactSaver named in the configuration's config
 * JSON and delegates persistence of the canonical model to it. Adding a new entity type's
 * persistence is a matter of adding a new ArtifactSaver bean — this class never changes.
 */
@Component
@RequiredArgsConstructor
public class SaverWorker extends AbstractWorker {

    private final List<ArtifactSaver>  savers;
    private final ModuleResolver       moduleResolver;
    private final PipelineRunService   pipelineRunService;
    private final RawDataRefRepository rawDataRefRepository;

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
        return List.of("artifactType", "artifactUid", "rawDataRefId", "configurationId");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        long rawDataRefId = ((Number) task.getVariables().get("rawDataRefId")).longValue();
        Long configurationId = ((Number) task.getVariables().get("configurationId")).longValue();
        Long runId = task.getVariables().get("pipelineRunId") instanceof Number n ? n.longValue() : null;

        // Idempotency guard: if a crash happened after this run already completed but before
        // Camunda recorded the task as done, a retry would otherwise duplicate the save.
        if (runId != null && pipelineRunService.isAlreadyCompleted(runId)) {
            log.info("Run {} already completed — skipping duplicate save for uid={}", runId, uid);
            return null;
        }

        String moduleCode = moduleResolver.resolve(configurationId, topic());
        ArtifactSaver saver = registry.get(moduleCode);
        if (saver == null) {
            throw new IllegalStateException("No ArtifactSaver registered for moduleCode=" + moduleCode);
        }

        log.info("stage=saver, module={}, uid={}", moduleCode, uid);

        RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

        Map<String, Object> result = saver.save(uid, type, rawDataRefId, runId, ref.getCanonicalSnapshotJson());

        // canonical_snapshot_json only existed to ferry the transformer's output to this
        // stage (instead of an oversized Camunda process variable) — now that it's
        // persisted, drop it so raw_data_refs doesn't keep growing indefinitely.
        ref.setCanonicalSnapshotJson(null);
        rawDataRefRepository.save(ref);

        if (runId != null) {
            pipelineRunService.completeRun(runId);
        }

        return result;
    }
}
