package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.TransformResult;

@Slf4j
@Component
@RequiredArgsConstructor
public class StructurizrSequenceTransformer implements ArtifactTransformer {

    public static final String MODULE_CODE = "structurizr-sequence-transformer";

    private final ObjectMapper objectMapper;
    private final StructurizrDynamicViewDecomposer decomposer;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Maps a Structurizr workspace export (product/container/tc/interface/operation/dynamicView) onto the canonical model"; }

    @Override
    public TransformResult transform(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);
        StructurizrDynamicViewDecomposer.Result result = decomposer.decompose(root, artifactUid);
        StructurizrSequenceSnapshot snapshot = result.snapshot();

        log.info("Transformed structurizr-sequence uid={}: containers={}, techCapabilities={}, interfaces={}, operations={}, " +
                        "sequences={}, sequenceRelations={}, operationRelations={}, notices={}",
                artifactUid, snapshot.getContainers().size(), snapshot.getTechCapabilities().size(), snapshot.getInterfaces().size(),
                snapshot.getOperations().size(), snapshot.getSequences().size(), snapshot.getSequenceRelations().size(),
                snapshot.getOperationRelations().size(), result.notices().size());

        return TransformResult.of(snapshot, result.notices());
    }
}
