package ru.beeline.staging.pipeline.validator;

import java.util.Map;

public interface ArtifactValidator {

    String moduleCode();

    String description();

    Map<String, Object> validate(String artifactUid, String rawContent) throws Exception;
}
