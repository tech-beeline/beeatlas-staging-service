/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.e2e;

import java.util.List;

/**
 * Единый отчёт о валидации PlantUML e2e (STG-03/STG-09) — общий JSON-контракт для обоих
 * синхронных методов (по тексту и по docId) и для UI.
 */
public record E2eValidationReport(
        boolean valid,
        List<RecognizedParticipant> recognizedParticipants,
        List<UnrecognizedParticipant> unrecognizedParticipants,
        List<RecognizedCall> recognizedCalls,
        List<UnrecognizedCall> unrecognizedCalls,
        List<ValidationNotice> notices
) {}
