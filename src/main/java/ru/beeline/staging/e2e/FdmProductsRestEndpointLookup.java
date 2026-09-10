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

@Slf4j
@Component
@RequiredArgsConstructor
public class FdmProductsRestEndpointLookup implements RestEndpointLookup {

    private final ProductServiceClient productServiceClient;

    @Override
    public boolean exists(String cmdbAlias, String cmdbName, String httpMethod, String path) {
        String matchPath = stripQueryString(path);
        List<OperationEntry> candidates = candidatesOwnedByReceiver(cmdbAlias, cmdbName, matchPath);
        if (candidates.stream().anyMatch(entry -> typeMatches(entry, httpMethod))) {
            return true;
        }
        return candidates.stream().anyMatch(FdmProductsRestEndpointLookup::hasUnknownType);
    }

    private static String stripQueryString(String path) {
        int idx = path.indexOf('?');
        return idx >= 0 ? path.substring(0, idx) : path;
    }

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
