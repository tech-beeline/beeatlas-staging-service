/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import java.util.regex.Pattern;

/**
 * Best-effort mapping of parsed diagram elements back to source line numbers (1-based).
 * <p>
 * PlantUML's public {@code sequencediagram} object model (Participant/Message) does not expose
 * the source line it was parsed from, so this locates elements by scanning the raw text instead —
 * the diagram structure itself still comes exclusively from the PlantUML parser.
 */
class SourceLineLocator {

    private final String[] lines;

    SourceLineLocator(String source) {
        this.lines = source.split("\n", -1);
    }

    /** First line containing {@code token} as a whole word — typically its declaration or first use. */
    int findFirstLine(String token) {
        Pattern pattern = wordBoundary(token);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                return i + 1;
            }
        }
        return 1;
    }

    /**
     * First arrow-looking line, at or after {@code fromLine} (1-based), mentioning both aliases —
     * falls back to scanning the whole text if nothing is found from that point on.
     */
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
        return line.indexOf('-') >= 0 && line.indexOf('>') >= 0;
    }

    private static Pattern wordBoundary(String token) {
        return Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
    }
}
