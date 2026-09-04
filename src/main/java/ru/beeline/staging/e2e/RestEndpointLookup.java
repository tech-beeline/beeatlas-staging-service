/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

/**
 * Checks whether a REST endpoint referenced by a PlantUML message call exists in the
 * BeeAtlas operations catalog, scoped to the specific receiving participant — an endpoint
 * that exists somewhere in the catalog but not on {@code cmdbAlias} does not count.
 */
public interface RestEndpointLookup {

    boolean exists(String cmdbAlias, String httpMethod, String path);
}
