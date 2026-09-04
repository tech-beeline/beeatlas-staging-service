/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.TransformResult;
import ru.beeline.staging.pipeline.transformer.ArtifactTransformer;
import ru.beeline.staging.repository.PipelineRunRepository;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class TransformerStage implements ArtifactPipelineStage {

    private static final int MAX_NOTICES = 200000;

    private final List<ArtifactTransformer> transformers;
    private final RawDataRefRepository      rawDataRefRepository;
    private final PipelineRunRepository     pipelineRunRepository;
    private final ObjectMapper              objectMapper;
    private final ModuleResolver            moduleResolver;
    private final PipelineRunService        pipelineRunService;

    private Map<String, ArtifactTransformer> registry;

    @PostConstruct
    void init() {
        registry = transformers.stream().collect(Collectors.toMap(ArtifactTransformer::moduleCode, t -> t));
        log.info("TransformerStage registry initialized for modules: {}", registry.keySet());
    }

    @Override
    public String stageName() {
        return "transformer";
    }

    @Override
    public void execute(Long runId) throws Exception {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("PipelineRun not found: " + runId));
        String uid = run.getArtifactUid();
        String artifactType = run.getArtifactType();
        long rawDataRefId = run.getRawDataRefId();

        Long stageLogId = pipelineRunService.startStage(runId, stageName(), "rawDataRefId=" + rawDataRefId);
        try {
            if (pipelineRunService.isAlreadyFullyProcessed(uid, artifactType, rawDataRefId)) {
                log.info("stage=transformer, uid={} — content unchanged and previously completed (rawDataRefId={}), skipping transform", uid, rawDataRefId);
                pipelineRunService.completeStage(stageLogId, "skipped: content unchanged", null);
                return;
            }

            String moduleCode = moduleResolver.resolve(artifactType, stageName());
            ArtifactTransformer transformer = registry.get(moduleCode);
            if (transformer == null) {
                throw new IllegalStateException("No ArtifactTransformer registered for moduleCode=" + moduleCode);
            }

            log.info("stage=transformer, module={}, uid={}", moduleCode, uid);

            RawDataRef ref = rawDataRefRepository.findById(rawDataRefId)
                    .orElseThrow(() -> new NoSuchElementException("RawDataRef not found: " + rawDataRefId));

            // TEMP: gzip disabled for easier manual inspection while debugging — see GzipUtils/SparxE2EAdapter.
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
            pipelineRunService.completeStage(stageLogId, "rawDataRefId=" + rawDataRefId, StageSupport.buildSummary(output));
        } catch (Exception e) {
            pipelineRunService.failStage(stageLogId, runId, stageName(), e.getMessage());
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
                    first.context(), first.rawDataContextId(),
                    first.artifactUid(), first.artifactName()));
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
