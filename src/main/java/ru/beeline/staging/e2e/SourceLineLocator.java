/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import java.util.regex.Pattern;

class SourceLineLocator {

    private final String[] lines;

    SourceLineLocator(String source) {
        this.lines = source.split("\n", -1);
    }

    int findFirstLine(String token) {
        Pattern pattern = wordBoundary(token);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                return i + 1;
            }
        }
        return 1;
    }

    int findMessageLine(String fromAlias, String toAlias, int fromLine) {
        Pattern p1 = wordBoundary(fromAlias);
        Pattern p2 = wordBoundary(toAlias);
        int searchStart = Math.max(0, fromLine - 1);
        for (int i = searchStart; i < lines.length; i++) {
            if (isArrowLine(lines[i]) && p1.matcher(lines[i]).find() && p2.matcher(lines[i]).find()) {
                return i + 1;
            }
        }
        for (int i = 0; i < searchStart; i++) {
            if (isArrowLine(lines[i]) && p1.matcher(lines[i]).find() && p2.matcher(lines[i]).find()) {
                return i + 1;
            }
        }
        return fromLine;
    }

    private static boolean isArrowLine(String line) {
        return line.indexOf('-') >= 0 && (line.indexOf('>') >= 0 || line.indexOf('<') >= 0);
    }

    private static Pattern wordBoundary(String token) {
        return Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
    }
}
