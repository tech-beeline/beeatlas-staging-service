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
    public boolean exists(String cmdbAlias, String cmdbName, String httpMethod, String path) {
        List<OperationEntry> candidates = candidatesOwnedByReceiver(cmdbAlias, cmdbName, path);
        if (candidates.stream().anyMatch(entry -> typeMatches(entry, httpMethod))) {
            return true;
        }
        // the catalog sometimes never captured the protocol at all (type=UNKNOWN, a placeholder from
        // import, not a real HTTP method) — accept that only as a last resort, so it doesn't mask a
        // genuine type mismatch when a better candidate exists
        return candidates.stream().anyMatch(FdmProductsRestEndpointLookup::hasUnknownType);
    }

    private List<OperationEntry> candidatesOwnedByReceiver(String cmdbAlias, String cmdbName, String path) {
        // no server-side type filter: an exact-type candidate and a type=UNKNOWN one are both needed
        // to decide between the two tiers above
        OperationSearchResponse response = productServiceClient.searchOperation(searchNeedle(path), null);
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
     * fdm-products matches the search path with a plain substring ILIKE — a concrete diagram path
     * like "/api/v1/graph/123" is never a substring of a templated catalog entry
     * "/api/v1/graph/{docId}", so searching with the full path can never surface it. Trimming to the
     * stable prefix before the first id-looking segment widens the net; {@link #pathMatches} still
     * does the precise check against the untrimmed path.
     * <p>
     * The leading slash is dropped too — some catalog entries are stored without one at all (e.g.
     * "getServiceList" rather than "/getServiceList"), and a needle with no slash is still a valid
     * (just broader) substring match against entries that do have one, so nothing is lost by dropping
     * it — {@link #pathMatches} still normalizes slashes on both sides for the precise check.
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
        return stable.isEmpty() ? path : String.join("/", stable);
    }

    private static boolean looksLikeVariableValue(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        boolean allDigits = segment.chars().allMatch(Character::isDigit);
        return allDigits || UUID_SEGMENT.matcher(segment).matches();
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
