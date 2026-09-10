/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.e2e;

public record E2eValidateRequest(String plantUml, String processName, String cjUid, String biStepCode) {}
