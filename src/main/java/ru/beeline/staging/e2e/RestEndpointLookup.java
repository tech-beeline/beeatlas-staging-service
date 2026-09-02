/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

/**
 * Checks whether a REST endpoint referenced by a PlantUML message call exists in the
 * BeeAtlas operations catalog.
 */
public interface RestEndpointLookup {

    boolean exists(String httpMethod, String path);
}
