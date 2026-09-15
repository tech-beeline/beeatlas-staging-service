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
        String newRequestJson) {

    public static final String MAP_EXISTING = "map_existing";
    public static final String CREATE_NEW = "create_new";
}
