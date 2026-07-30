package ru.beeline.staging.dto.notice;

import java.util.List;
import java.util.Map;

public record SaveResult(Map<String, Object> summary, List<ArtifactNotice> notices) {

    public static SaveResult of(Map<String, Object> summary) {
        return new SaveResult(summary, List.of());
    }
}
