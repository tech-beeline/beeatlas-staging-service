package ru.beeline.staging.pipeline.preadapter;

import ru.beeline.staging.domain.Configuration;

import java.util.List;
import java.util.Map;

public interface ArtifactPreAdapter {

    String moduleCode();

    String description();

    List<FoundArtifact> scan(Configuration config);

    record FoundArtifact(String uid, Map<String, Object> metadata) {}
}
