package ru.beeline.staging.pipeline.transformer;

public interface ArtifactTransformer {

    String moduleCode();

    String description();

    Object transform(String artifactUid, String rawContent) throws Exception;
}
