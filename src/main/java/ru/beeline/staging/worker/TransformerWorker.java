package ru.beeline.staging.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.TransformResult;
import ru.beeline.staging.pipeline.transformer.ArtifactTransformer;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.service.ModuleResolver;
import ru.beeline.staging.service.PipelineRunService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TransformerWorker extends AbstractWorker {

    private static final int MAX_NOTICES = 2000;

    private final List<ArtifactTransformer> transformers;
    private final RawDataRefRepository      rawDataRefRepository;
    private final ObjectMapper              objectMapper;
    private final ModuleResolver            moduleResolver;
    private final PipelineRunService        pipelineRunService;

    private Map<String, ArtifactTransformer> registry;

    @PostConstruct
    void init() {
        registry = transformers.stream().collect(Collectors.toMap(ArtifactTransformer::moduleCode, t -> t));
        log.info("TransformerWorker registry initialized for modules: {}", registry.keySet());
    }

    @Override
    protected String topic() { return "transformer"; }

    @Override
    protected String workerId() { return "staging-transformer-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactType", "artifactUid", "rawDataRefId", "configurationId", "pipelineRunId");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) throws Exception {
        String uid  = (String) task.getVariables().get("artifactUid");
        String artifactType = (String) task.getVariables().get("artifactType");
        long rawDataRefId = ((Number) task.getVariables().get("rawDataRefId")).longValue();
        Long runId = ((Number) task.getVariables().get("pipelineRunId")).longValue();

        Long stageLogId = pipelineRunService.startStage(runId, "transformer", "rawDataRefId=" + rawDataRefId);
        try {
            String moduleCode = moduleResolver.resolve(artifactType, topic());
            ArtifactTransformer transformer = registry.get(moduleCode);
            if (transformer == null) {
                throw new IllegalStateException("No ArtifactTransformer registered for moduleCode=" + moduleCode);
            }

            log.info("stage=transformer, module={}, uid={}", moduleCode, uid);

            RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                    .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

            // TEMP: gzip disabled for easier manual inspection while debugging — see GzipUtils/SparxE2EAdapter.
            // TransformResult result = transformer.transform(uid, GzipUtils.gunzipToString(ref.getRawContent()));
            TransformResult result = transformer.transform(uid, new String(ref.getRawContent(), StandardCharsets.UTF_8));
            if (result.notices().size() > MAX_NOTICES) {
                throw new IllegalStateException("Transform produced " + result.notices().size()
                        + " notices for uid=" + uid + " (> " + MAX_NOTICES + ") — likely duplicate/cyclic raw data, refusing to save");
            }
            String snapshotJson = objectMapper.writeValueAsString(result.snapshot());

            ref.setCanonicalSnapshotJson(snapshotJson);
            rawDataRefRepository.save(ref);

            List<ArtifactNotice> saved = pipelineRunService.saveNotices(rawDataRefId, result.notices());
            long errorCount = saved.stream().filter(n -> "error".equals(n.level())).count();
            if (errorCount > 0) {
                throw new IllegalStateException("Transform failed: " + errorCount + " error notice(s) for uid=" + uid);
            }

            Map<String, Object> output = Map.of(
                    "rawDataRefId", rawDataRefId,
                    "canonicalSnapshotBytes", snapshotJson.length(),
                    "noticeCount", (long) result.notices().size()
            );
            pipelineRunService.completeStage(stageLogId, "rawDataRefId=" + rawDataRefId, buildSummary(output));
            return output;
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, "transformer", e.getMessage());
            throw e;
        }
    }
}
