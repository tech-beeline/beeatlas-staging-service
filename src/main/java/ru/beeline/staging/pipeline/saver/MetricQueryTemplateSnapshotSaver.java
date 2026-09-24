package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactBatch;
import ru.beeline.staging.pipeline.transformer.MetricQueriesObjectPublish;
import ru.beeline.staging.service.PipelineRunService;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueryTemplateSnapshotSaver {

    private final PipelineRunService              pipelineRunService;
    private final MetricQueryTemplateMatchService matchService;

    @Transactional
    public Long saveSnapshot(MetricQueriesObjectPublish snapshot, Long rawDataRefId,
                              Long runId, String artifactUid, String artifactType) throws Exception {
        ArtifactBatch batch = pipelineRunService.createBatch(artifactUid, artifactType, runId, rawDataRefId, 0, 0, 0);

        matchService.matchOrCreate(snapshot.uid(), snapshot.entityType(), snapshot.schemaVersion(),
                snapshot.metricTemplates(), rawDataRefId, batch.getId());

        return batch.getId();
    }
}
