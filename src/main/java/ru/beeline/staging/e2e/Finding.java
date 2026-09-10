/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

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
