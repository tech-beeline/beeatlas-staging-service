package ru.beeline.staging.dto.search;

import java.util.List;

public record ArtifactSearchPage(
        long totalCount,
        List<ArtifactSearchResult> results
) {}
