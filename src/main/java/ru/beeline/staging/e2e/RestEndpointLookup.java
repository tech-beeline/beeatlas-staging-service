/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

public interface RestEndpointLookup {

    boolean exists(String productAlias, String httpMethod, String path);
}
