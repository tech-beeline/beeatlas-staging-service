/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.e2e;

/**
 * Тело запроса {@code POST /api/v1/e2e/validate} (STG-01). {@code processName}/{@code cjUid}/
 * {@code biStepCode} — опциональный контекст процесса, не влияет на правила валидации v1.
 */
public record E2eValidateRequest(String plantUml, String processName, String cjUid, String biStepCode) {}
