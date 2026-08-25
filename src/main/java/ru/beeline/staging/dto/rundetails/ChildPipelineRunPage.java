package ru.beeline.staging.dto.rundetails;

import java.util.List;

public record ChildPipelineRunPage(
        long totalCount,
        List<ChildPipelineRun> results
) {}
