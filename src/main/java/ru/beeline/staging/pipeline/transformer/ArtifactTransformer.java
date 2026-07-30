package ru.beeline.staging.pipeline.transformer;

import ru.beeline.staging.dto.notice.TransformResult;

public interface ArtifactTransformer {

    String moduleCode();

    String description();

    TransformResult transform(String artifactUid, String rawContent) throws Exception;
}
