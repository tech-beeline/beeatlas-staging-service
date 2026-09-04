/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.e2e;

import ru.beeline.staging.e2e.Finding;

public record ValidationNotice(
        String code,
        String level,
        String message,
        Integer lineFrom,
        Integer lineTo,
        String elementRef
) {

    private static final String SOURCE_LINES_SEPARATOR = " || ";
    private static final String SOURCE_LINE_DELIMITER = " | ";

    /**
     * {@code sourceText} is the original PlantUML input the finding was raised against — its
     * {@code lineFrom..lineTo} lines are appended (delimiter-joined) to {@code message}, so the
     * frontend can show the actual offending source without a DTO change or a frontend change.
     */
    public static ValidationNotice from(Finding finding, String sourceText) {
        return new ValidationNotice(
                finding.code(),
                finding.level().name().toLowerCase(),
                appendSourceLines(finding.message(), finding.lineFrom(), finding.lineTo(), sourceText),
                finding.lineFrom(),
                finding.lineTo(),
                finding.elementRef());
    }

    private static String appendSourceLines(String message, Integer lineFrom, Integer lineTo, String sourceText) {
        if (lineFrom == null || sourceText == null) {
            return message;
        }
        String[] lines = sourceText.split("\n", -1);
        int from = Math.max(1, lineFrom);
        int to = Math.max(from, lineTo != null ? lineTo : lineFrom);
        StringBuilder snippet = new StringBuilder();
        for (int i = from; i <= to && i <= lines.length; i++) {
            if (snippet.length() > 0) {
                snippet.append(SOURCE_LINE_DELIMITER);
            }
            snippet.append(lines[i - 1].strip());
        }
        return snippet.length() == 0 ? message : message + SOURCE_LINES_SEPARATOR + snippet;
    }
}
