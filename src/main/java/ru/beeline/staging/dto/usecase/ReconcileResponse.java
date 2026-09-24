/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.usecase;

public record ReconcileResponse(
        Long reconcileId,
        String status,
        int matched,
        int remainingRequired) {
}
