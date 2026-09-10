/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.e2e;

import java.util.List;

public record E2eValidationReport(
        boolean valid,
        List<RecognizedParticipant> recognizedParticipants,
        List<UnrecognizedParticipant> unrecognizedParticipants,
        List<RecognizedCall> recognizedCalls,
        List<UnrecognizedCall> unrecognizedCalls,
        List<ValidationNotice> notices
) {}
