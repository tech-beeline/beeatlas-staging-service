/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.usecase;

public record ImportDecision(
        Long id,
        Long runId,
        String partId,
        String decisionType,
        String targetJson,
        String connectionOperationJson) {

    public static final String MAP_EXISTING = "map_existing";
    public static final String PLANNED = "planned";
}
