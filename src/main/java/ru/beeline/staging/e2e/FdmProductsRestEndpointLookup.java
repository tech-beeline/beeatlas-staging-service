/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.product.dto.OperationEntry;
import ru.beeline.staging.product.dto.OperationSearchResponse;

import java.util.List;
import java.util.stream.Stream;

/**
 * fdm-products {@code GET /api/v1/operation} matches {@code path} with a substring ILIKE
 * (see OperationRepository.findArchOperationsProjectionByType) — this class re-filters the
 * result for an exact/template path match, since a substring hit is not "this endpoint exists".
 * <p>
 * It also re-filters for ownership: the search itself is not scoped to a system/container, so
 * a hit is only counted if the returned entry's {@code product.alias} or {@code container.code}
 * matches the message's receiver — {@code /operation} has no such scoping param, but each
 * returned entry already carries this, so filtering client-side needs no new fdm-products API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FdmProductsRestEndpointLookup implements RestEndpointLookup {

    private final ProductServiceClient productServiceClient;

    @Override
    public boolean exists(String cmdbAlias, String httpMethod, String path) {
        OperationSearchResponse response = productServiceClient.searchOperation(path, httpMethod);
        List<OperationEntry> archOperations = response.getArchOperations();
        List<OperationEntry> discoveredOperations = response.getDiscoveredOperations();
        return Stream.concat(
                        archOperations != null ? archOperations.stream() : Stream.empty(),
                        discoveredOperations != null ? discoveredOperations.stream() : Stream.empty())
                .anyMatch(entry -> pathMatches(path, entry.getName())
                        && (entry.getType() == null || entry.getType().equalsIgnoreCase(httpMethod))
                        && belongsTo(entry, cmdbAlias));
    }

    private static boolean belongsTo(OperationEntry entry, String cmdbAlias) {
        boolean productMatch = entry.getProduct() != null && cmdbAlias.equalsIgnoreCase(entry.getProduct().getAlias());
        boolean containerMatch = entry.getContainer() != null && cmdbAlias.equalsIgnoreCase(entry.getContainer().getCode());
        return productMatch || containerMatch;
    }

    /** Segment-by-segment match, treating any {@code {param}}-shaped template segment as a wildcard. */
    private static boolean pathMatches(String requestedPath, String templatePath) {
        if (templatePath == null) {
            return false;
        }
        if (requestedPath.equalsIgnoreCase(templatePath)) {
            return true;
        }
        String[] requestedSegments = splitPath(requestedPath);
        String[] templateSegments = splitPath(templatePath);
        if (requestedSegments.length != templateSegments.length) {
            return false;
        }
        for (int i = 0; i < requestedSegments.length; i++) {
            String templateSegment = templateSegments[i];
            boolean isPlaceholder = templateSegment.indexOf('{') >= 0;
            if (!isPlaceholder && !templateSegment.equalsIgnoreCase(requestedSegments[i])) {
                return false;
            }
        }
        return true;
    }

    private static String[] splitPath(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        return trimmed.split("/");
    }
}
