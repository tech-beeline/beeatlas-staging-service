package ru.beeline.staging.e2e;

import org.junit.jupiter.api.Test;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.product.dto.OperationEntry;
import ru.beeline.staging.product.dto.OperationSearchResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FdmProductsRestEndpointLookupTest {

    private final ProductServiceClient productServiceClient = mock(ProductServiceClient.class);
    private final FdmProductsRestEndpointLookup lookup = new FdmProductsRestEndpointLookup(productServiceClient);

    @Test
    void doesNotCountAMatchOwnedByAnotherSystem() {
        when(productServiceClient.searchOperation(anyString(), anyString()))
                .thenReturn(response(operation("/api/v1/order", "POST", "billing", null)));

        assertThat(lookup.exists("crm", "POST", "/api/v1/order")).isFalse();
        assertThat(lookup.exists("billing", "POST", "/api/v1/order")).isTrue();
    }

    @Test
    void matchesAContainerScopedEndpointByContainerCode() {
        when(productServiceClient.searchOperation(anyString(), anyString()))
                .thenReturn(response(operation("/api/v1/order", "POST", null, "order-container")));

        assertThat(lookup.exists("order-container", "POST", "/api/v1/order")).isTrue();
    }

    @Test
    void matchesAPathTemplateAgainstAConcretePath() {
        when(productServiceClient.searchOperation(anyString(), anyString()))
                .thenReturn(response(operation("/api/v1/graph/{docId}", "GET", "arch-graph", null)));

        assertThat(lookup.exists("arch-graph", "GET", "/api/v1/graph/123")).isTrue();
    }

    @Test
    void doesNotMatchATemplateWithADifferentNumberOfSegments() {
        when(productServiceClient.searchOperation(anyString(), anyString()))
                .thenReturn(response(operation("/api/v1/graph/{docId}", "GET", "arch-graph", null)));

        assertThat(lookup.exists("arch-graph", "GET", "/api/v1/graph/123/extra")).isFalse();
    }

    private static OperationSearchResponse response(OperationEntry entry) {
        OperationSearchResponse response = new OperationSearchResponse();
        response.setArchOperations(List.of(entry));
        response.setDiscoveredOperations(List.of());
        return response;
    }

    private static OperationEntry operation(String path, String method, String productAlias, String containerCode) {
        OperationEntry entry = new OperationEntry();
        entry.setName(path);
        entry.setType(method);
        if (productAlias != null) {
            OperationEntry.ProductRef product = new OperationEntry.ProductRef();
            product.setAlias(productAlias);
            entry.setProduct(product);
        }
        if (containerCode != null) {
            OperationEntry.ContainerRef container = new OperationEntry.ContainerRef();
            container.setCode(containerCode);
            entry.setContainer(container);
        }
        return entry;
    }
}
