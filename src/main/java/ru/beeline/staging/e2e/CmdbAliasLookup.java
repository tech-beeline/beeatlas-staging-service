/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import java.util.Map;
import java.util.Set;

public interface CmdbAliasLookup {

    Map<String, ResolvedParticipant> resolveAll(Set<String> aliases);

    record ResolvedParticipant(String alias, String name, Kind kind, String productAlias,
                               ResolvedParticipant competingWith) {

        public ResolvedParticipant(String alias, String name, Kind kind) {
            this(alias, name, kind, alias, null);
        }

        public ResolvedParticipant(String alias, String name, Kind kind, String productAlias) {
            this(alias, name, kind, productAlias, null);
        }

        public boolean ambiguous() {
            return competingWith != null;
        }

        public enum Kind { SYSTEM, CONTAINER }
    }
}
