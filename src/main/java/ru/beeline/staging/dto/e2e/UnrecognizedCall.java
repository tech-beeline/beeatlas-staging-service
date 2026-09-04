/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.e2e;

public record UnrecognizedCall(String fromAlias, String toAlias, String label, int line) {}
