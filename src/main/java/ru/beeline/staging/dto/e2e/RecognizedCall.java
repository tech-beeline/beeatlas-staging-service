/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.e2e;

public record RecognizedCall(String fromAlias, String toAlias, String httpMethod, String path, int line) {}
