/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import ru.beeline.staging.dto.e2e.RecognizedCall;
import ru.beeline.staging.dto.e2e.RecognizedParticipant;
import ru.beeline.staging.dto.e2e.UnrecognizedCall;
import ru.beeline.staging.dto.e2e.UnrecognizedParticipant;

import java.util.List;

public record EngineResult(
        List<RecognizedParticipant> recognizedParticipants,
        List<UnrecognizedParticipant> unrecognizedParticipants,
        List<RecognizedCall> recognizedCalls,
        List<UnrecognizedCall> unrecognizedCalls,
        List<Finding> findings
) {
    public boolean valid() {
        return findings.stream().noneMatch(f -> f.level() == Finding.Level.ERROR);
    }
}
