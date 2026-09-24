/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ParticipantLookup {

    private static final String PARTICIPANT_KIND = "PARTICIPANT";

    private ParticipantLookup() {
    }

    public static boolean isProduct(String declaredKind) {
        return declaredKind == null || PARTICIPANT_KIND.equalsIgnoreCase(declaredKind);
    }

    public static Set<String> keysOf(String name) {
        Set<String> keys = new LinkedHashSet<>();
        if (name == null || name.isBlank()) {
            return keys;
        }
        String trimmed = name.trim();
        keys.add(trimmed);
        int firstDot = trimmed.indexOf('.');
        if (firstDot > 0) {
            keys.add(trimmed.substring(0, firstDot));
            int lastDot = trimmed.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < trimmed.length() - 1) {
                keys.add(trimmed.substring(lastDot + 1));
            }
        }
        return keys;
    }

    public static Set<String> keysOf(ParsedDiagram diagram) {
        Set<String> keys = new LinkedHashSet<>();
        for (ParsedDiagram.Participant participant : diagram.participants()) {
            if (isProduct(participant.declaredKind())) {
                keys.addAll(keysOf(participant.name()));
            }
        }
        return keys;
    }

    public static CmdbAliasLookup.ResolvedParticipant resolve(
            Map<String, CmdbAliasLookup.ResolvedParticipant> resolved, ParsedDiagram.Participant participant) {

        for (String key : keysOf(participant.name())) {
            CmdbAliasLookup.ResolvedParticipant match = resolved.get(key);
            if (match != null && match.kind() == CmdbAliasLookup.ResolvedParticipant.Kind.SYSTEM) {
                return match;
            }
        }
        return null;
    }
}
