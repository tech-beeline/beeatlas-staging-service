/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

import java.util.Map;
import java.util.stream.Collectors;

public final class StageSupport {

    private StageSupport() {
    }

    /** Scalar/boolean fields only, kept for quick dashboards via pipeline_stage_logs.summary_json. */
    public static Map<String, Object> buildSummary(Map<String, Object> outputVars) {
        if (outputVars == null || outputVars.isEmpty()) return null;
        return outputVars.entrySet().stream()
                .filter(e -> e.getValue() instanceof Number || e.getValue() instanceof Boolean)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
