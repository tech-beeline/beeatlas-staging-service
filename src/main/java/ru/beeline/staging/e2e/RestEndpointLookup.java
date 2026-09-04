/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

/**
 * Checks whether a REST endpoint referenced by a PlantUML message call exists in the
 * BeeAtlas operations catalog, scoped to the specific receiving participant — an endpoint
 * that exists somewhere in the catalog but not on the receiver does not count.
 * <p>
 * The receiver's CMDB landscape can be split across several disconnected product/container
 * records that share the same display name (CMDB data-quality gap, not a modelling choice —
 * e.g. one system represented as both a container and a separate product, each with its own,
 * non-overlapping set of operations). {@code cmdbName} lets a match land on any of them, not
 * just the one record {@code cmdbAlias} happened to resolve to.
 */
public interface RestEndpointLookup {

    boolean exists(String cmdbAlias, String cmdbName, String httpMethod, String path);
}
