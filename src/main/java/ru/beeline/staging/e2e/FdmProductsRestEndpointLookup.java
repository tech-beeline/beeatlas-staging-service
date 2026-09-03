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
 * result for an exact path match, since a substring hit is not "this endpoint exists".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FdmProductsRestEndpointLookup implements RestEndpointLookup {

    private final ProductServiceClient productServiceClient;

    @Override
    public boolean exists(String httpMethod, String path) {
        OperationSearchResponse response = productServiceClient.searchOperation(path, httpMethod);
        List<OperationEntry> archOperations = response.getArchOperations();
        List<OperationEntry> discoveredOperations = response.getDiscoveredOperations();
        return Stream.concat(
                        archOperations != null ? archOperations.stream() : Stream.empty(),
                        discoveredOperations != null ? discoveredOperations.stream() : Stream.empty())
                .anyMatch(entry -> path.equalsIgnoreCase(entry.getName())
                        && (entry.getType() == null || entry.getType().equalsIgnoreCase(httpMethod)));
    }
}
