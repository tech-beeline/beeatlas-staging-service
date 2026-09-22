/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.product.dto.search.MatchedArchOperation;
import ru.beeline.staging.product.dto.search.OperationMatchCandidate;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FdmProductsRestEndpointLookup implements RestEndpointLookup {

    private final ProductServiceClient productServiceClient;

    @Override
    public boolean exists(String productAlias, String httpMethod, String path) {
        if (productAlias == null || productAlias.isBlank()) {
            return false;
        }
        List<MatchedArchOperation> matches = productServiceClient.searchMatchedOperations(
                List.of(new OperationMatchCandidate(path, httpMethod, null, productAlias)));
        return matches.stream().anyMatch(FdmProductsRestEndpointLookup::isLandscapeOperation);
    }

    private static boolean isLandscapeOperation(MatchedArchOperation match) {
        return !Boolean.TRUE.equals(match.getNotFound())
                && match.getName() != null
                && match.getInterfaceObj() != null
                && match.getInterfaceObj().getCode() != null;
    }
}
