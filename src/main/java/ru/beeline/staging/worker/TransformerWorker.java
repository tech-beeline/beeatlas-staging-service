/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TransformerWorker extends AbstractWorker {

    private static final int MAX_NOTICES = 200000;

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
            if (pipelineRunService.isAlreadyFullyProcessed(uid, artifactType, rawDataRefId)) {
                log.info("stage=transformer, uid={} — content unchanged and previously completed (rawDataRefId={}), skipping transform", uid, rawDataRefId);
                pipelineRunService.completeStage(stageLogId, "skipped: content unchanged", null);
                return Map.of("rawDataRefId", rawDataRefId, "skipped", true);
            }

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
            String snapshotJson = objectMapper.writeValueAsString(result.snapshot());

            ref.setCanonicalSnapshotJson(snapshotJson);
            rawDataRefRepository.save(ref);

            List<ArtifactNotice> noticesToSave = result.notices().size() > MAX_NOTICES
                    ? aggregateByCodeAndReason(result.notices())
                    : result.notices();
            List<ArtifactNotice> saved = pipelineRunService.saveNotices(rawDataRefId, noticesToSave);
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

    private List<ArtifactNotice> aggregateByCodeAndReason(List<ArtifactNotice> notices) {
        Map<String, List<ArtifactNotice>> grouped = notices.stream()
                .collect(Collectors.groupingBy(
                        n -> n.code() + "|" + n.level() + "|" + reasonOf(n.details()),
                        LinkedHashMap::new, Collectors.toList()));

        List<ArtifactNotice> result = new ArrayList<>();
        for (List<ArtifactNotice> group : grouped.values()) {
            ArtifactNotice first = group.get(0);
            if (group.size() == 1) {
                result.add(first);
                continue;
            }
            result.add(new ArtifactNotice(
                    first.id(), first.noticeTypeId(), first.code(), first.level(), first.category(),
                    first.rawDataRefId(), first.entityType(), first.entityUid(), first.entityVersionId(),
                    first.message(), withOccurrenceCount(first.details(), group.size()),
                    first.context(), first.rawDataContextId()));
        }
        return result;
    }

    private String reasonOf(String detailsJson) {
        try {
            JsonNode reason = objectMapper.readTree(detailsJson).path("reason");
            return reason.isMissingNode() ? "" : reason.asText();
        } catch (Exception e) {
            return "";
        }
    }

    private String withOccurrenceCount(String detailsJson, int count) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(detailsJson);
            node.put("occurrence_count", count);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return detailsJson;
        }
    }
}
