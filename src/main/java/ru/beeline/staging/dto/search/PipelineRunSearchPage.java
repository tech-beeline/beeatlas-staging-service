package ru.beeline.staging.dto.search;

import java.util.List;

public record PipelineRunSearchPage(
        long totalCount,
        List<PipelineRunSearchResult> results
) {}
