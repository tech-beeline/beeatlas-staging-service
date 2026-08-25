package ru.beeline.staging.dto.search;

import java.util.List;

public record PipelineRunSearchHit(
        long startOffset,
        long endOffset,
        List<PipelineRunSearchHitContext> contexts,
        String snippet
) {}
