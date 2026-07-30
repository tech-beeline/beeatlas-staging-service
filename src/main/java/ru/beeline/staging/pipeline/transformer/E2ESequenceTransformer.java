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
public class E2ESequenceTransformer implements ArtifactTransformer {

    public static final String MODULE_CODE = "e2e-sequence-transformer";

    private final ObjectMapper objectMapper;
    private final ScenarioDecomposer decomposer;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Builds the call tree from the raw Sparx EA export and decomposes it into bi_step/interface/operation drafts"; }

    @Override
    public TransformResult transform(String artifactUid, String rawContent) throws Exception {
        JsonNode root = objectMapper.readTree(rawContent);
        ScenarioDecomposer.Result result = decomposer.decompose(root, artifactUid);
        E2ESequenceSnapshot snapshot = result.snapshot();

        log.info("Transformed e2e-sequence uid={}: interfaces={}, operations={}, biSteps={}, operationRelations={}, notices={}",
                artifactUid, snapshot.getInterfaces().size(), snapshot.getOperations().size(),
                snapshot.getBiSteps().size(), snapshot.getOperationRelations().size(),
                result.notices().size());

        return TransformResult.of(snapshot, result.notices());
    }
}
