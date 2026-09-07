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

import java.util.Arrays;
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
    public boolean exists(String cmdbAlias, String cmdbName, String httpMethod, String path) {
        // a query string is never part of the operation's path in CMDB — matching (search AND the
        // precise segment check) must ignore it, or it poisons both
        String matchPath = stripQueryString(path);
        List<OperationEntry> candidates = candidatesOwnedByReceiver(cmdbAlias, cmdbName, matchPath);
        if (candidates.stream().anyMatch(entry -> typeMatches(entry, httpMethod))) {
            return true;
        }
        // the catalog sometimes never captured the protocol at all (type=UNKNOWN, a placeholder from
        // import, not a real HTTP method) — accept that only as a last resort, so it doesn't mask a
        // genuine type mismatch when a better candidate exists
        return candidates.stream().anyMatch(FdmProductsRestEndpointLookup::hasUnknownType);
    }

    private static String stripQueryString(String path) {
        int idx = path.indexOf('?');
        return idx >= 0 ? path.substring(0, idx) : path;
    }

    /**
     * fdm-products matches the search path with a plain substring ILIKE — a concrete diagram path
     * like "/api/v4/systems/BLN" is never a substring of a templated catalog entry
     * "/api/v4/systems/{code}", so searching with the untrimmed path can miss it. There's no reliable
     * way to tell from the string alone which segment stands in for a template value — a numeric id
     * and a UUID are recognizable, but a system mnemonic like "BLN" or a process uid like "abc-123"
     * look just like an ordinary path segment. So instead of guessing, this tries the full path first
     * (best precision for a plain, non-templated operation), then progressively drops trailing
     * segments and searches again, until a needle turns up an owned, path-matching candidate.
     * {@link #pathMatches} still does the precise, template-aware check against the untrimmed path
     * for whatever candidates a needle returns — a broader needle only means more candidates to filter,
     * never a wrong match.
     */
    private List<OperationEntry> candidatesOwnedByReceiver(String cmdbAlias, String cmdbName, String path) {
        String[] segments = splitPath(path);
        for (int keep = segments.length; keep >= 1; keep--) {
            String needle = String.join("/", Arrays.copyOfRange(segments, 0, keep));
            List<OperationEntry> candidates = searchAndFilter(needle, path, cmdbAlias, cmdbName);
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }
        return List.of();
    }

    private List<OperationEntry> searchAndFilter(String needle, String path, String cmdbAlias, String cmdbName) {
        // no server-side type filter: an exact-type candidate and a type=UNKNOWN one are both needed
        // to decide between the two tiers in exists()
        OperationSearchResponse response = productServiceClient.searchOperation(needle, null);
        List<OperationEntry> archOperations = response.getArchOperations();
        List<OperationEntry> discoveredOperations = response.getDiscoveredOperations();
        return Stream.concat(
                        archOperations != null ? archOperations.stream() : Stream.empty(),
                        discoveredOperations != null ? discoveredOperations.stream() : Stream.empty())
                .filter(entry -> pathMatches(path, entry.getName()) && belongsTo(entry, cmdbAlias, cmdbName))
                .toList();
    }

    private static boolean typeMatches(OperationEntry entry, String httpMethod) {
        return entry.getType() == null || entry.getType().equalsIgnoreCase(httpMethod);
    }

    private static boolean hasUnknownType(OperationEntry entry) {
        return "UNKNOWN".equalsIgnoreCase(entry.getType());
    }

    /**
     * A hit counts if it's owned by the exact CMDB record the receiver resolved to (by code), or —
     * since that record can be one of several disconnected duplicates sharing a display name — by
     * any other record with the same name. Several candidates may end up matching; which one "wins"
     * is unspecified for now (first hit in whatever order the catalog returns).
     */
    private static boolean belongsTo(OperationEntry entry, String cmdbAlias, String cmdbName) {
        boolean productAliasMatch = entry.getProduct() != null && cmdbAlias.equalsIgnoreCase(entry.getProduct().getAlias());
        boolean containerCodeMatch = entry.getContainer() != null && cmdbAlias.equalsIgnoreCase(entry.getContainer().getCode());
        if (productAliasMatch || containerCodeMatch) {
            return true;
        }
        if (cmdbName == null) {
            return false;
        }
        boolean productNameMatch = entry.getProduct() != null && cmdbName.equalsIgnoreCase(entry.getProduct().getName());
        boolean containerNameMatch = entry.getContainer() != null && cmdbName.equalsIgnoreCase(entry.getContainer().getName());
        return productNameMatch || containerNameMatch;
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
