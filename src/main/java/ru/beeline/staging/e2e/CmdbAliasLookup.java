/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import java.util.Map;
import java.util.Set;

/**
 * Resolves PlantUML participant aliases against the BeeAtlas CMDB landscape (system/container).
 * Aliases not present in the returned map are unrecognized.
 */
public interface CmdbAliasLookup {

    Map<String, ResolvedParticipant> resolveAll(Set<String> aliases);

    record ResolvedParticipant(String alias, String name, Kind kind, String productAlias) {

        public ResolvedParticipant(String alias, String name, Kind kind) {
            this(alias, name, kind, alias);
        }

        public enum Kind { SYSTEM, CONTAINER }
    }
}
