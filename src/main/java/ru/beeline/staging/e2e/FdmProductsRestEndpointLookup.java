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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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

    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ProductServiceClient productServiceClient;

    @Override
    public boolean exists(String cmdbAlias, String httpMethod, String path) {
        OperationSearchResponse response = productServiceClient.searchOperation(searchNeedle(path), httpMethod);
        List<OperationEntry> archOperations = response.getArchOperations();
        List<OperationEntry> discoveredOperations = response.getDiscoveredOperations();
        return Stream.concat(
                        archOperations != null ? archOperations.stream() : Stream.empty(),
                        discoveredOperations != null ? discoveredOperations.stream() : Stream.empty())
                .anyMatch(entry -> pathMatches(path, entry.getName())
                        && (entry.getType() == null || entry.getType().equalsIgnoreCase(httpMethod))
                        && belongsTo(entry, cmdbAlias));
    }

    /**
     * fdm-products matches the search path with a plain substring ILIKE — a concrete diagram path
     * like "/api/v1/graph/123" is never a substring of a templated catalog entry
     * "/api/v1/graph/{docId}", so searching with the full path can never surface it. Trimming to the
     * stable prefix before the first id-looking segment widens the net; {@link #pathMatches} still
     * does the precise check against the untrimmed path.
     */
    private static String searchNeedle(String path) {
        String[] segments = splitPath(path);
        List<String> stable = new ArrayList<>();
        for (String segment : segments) {
            if (looksLikeVariableValue(segment)) {
                break;
            }
            stable.add(segment);
        }
        return stable.isEmpty() ? path : "/" + String.join("/", stable);
    }

    private static boolean looksLikeVariableValue(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        boolean allDigits = segment.chars().allMatch(Character::isDigit);
        return allDigits || UUID_SEGMENT.matcher(segment).matches();
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
