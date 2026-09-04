/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

/**
 * A single validation remark produced by {@link PlantUmlValidationEngine}. Domain-level —
 * unlike {@link ru.beeline.staging.dto.notice.ArtifactNotice} it carries no persistence
 * identifiers, since the synchronous e2e validation contour never touches the database.
 */
public record Finding(
        String code,
        Level level,
        String message,
        Integer lineFrom,
        Integer lineTo,
        String elementRef
) {

    public enum Level {
        INFO, WARNING, ERROR
    }

    public static Finding error(String code, String message, Integer lineFrom, Integer lineTo, String elementRef) {
        return new Finding(code, Level.ERROR, message, lineFrom, lineTo, elementRef);
    }

    public static Finding warning(String code, String message, Integer lineFrom, Integer lineTo, String elementRef) {
        return new Finding(code, Level.WARNING, message, lineFrom, lineTo, elementRef);
    }

    public static Finding info(String code, String message, Integer lineFrom, Integer lineTo, String elementRef) {
        return new Finding(code, Level.INFO, message, lineFrom, lineTo, elementRef);
    }
}
