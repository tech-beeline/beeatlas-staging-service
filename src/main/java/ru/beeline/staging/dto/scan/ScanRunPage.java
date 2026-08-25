package ru.beeline.staging.dto.scan;

import java.util.List;

public record ScanRunPage(
        long totalCount,
        List<ScanRun> results
) {}
