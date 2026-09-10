package ru.beeline.staging.e2e;

import org.junit.jupiter.api.Test;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.product.dto.OperationEntry;
import ru.beeline.staging.product.dto.OperationSearchResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FdmProductsRestEndpointLookupTest {

    private final ProductServiceClient productServiceClient = mock(ProductServiceClient.class);
    private final FdmProductsRestEndpointLookup lookup = new FdmProductsRestEndpointLookup(productServiceClient);

    @Test
    void doesNotCountAMatchOwnedByAnotherSystem() {
        when(productServiceClient.searchOperation(anyString(), isNull()))
                .thenReturn(response(operation("/api/v1/order", "POST", "billing", "Billing", null, null)));

        assertThat(lookup.exists("crm", "CRM", "POST", "/api/v1/order")).isFalse();
        assertThat(lookup.exists("billing", "Billing", "POST", "/api/v1/order")).isTrue();
    }

    @Test
    void matchesAContainerScopedEndpointByContainerCode() {
        when(productServiceClient.searchOperation(anyString(), isNull()))
                .thenReturn(response(operation("/api/v1/order", "POST", null, null, "order-container", "Order Container")));

        assertThat(lookup.exists("order-container", "Order Container", "POST", "/api/v1/order")).isTrue();
    }

    @Test
    void matchesAPathTemplateAgainstAConcretePath() {
        when(productServiceClient.searchOperation(anyString(), isNull()))
                .thenReturn(response(operation("/api/v1/graph/{docId}", "GET", "arch-graph", "Arch Graph", null, null)));

        assertThat(lookup.exists("arch-graph", "Arch Graph", "GET", "/api/v1/graph/123")).isTrue();
    }

    @Test
    void doesNotMatchATemplateWithADifferentNumberOfSegments() {
        when(productServiceClient.searchOperation(anyString(), isNull()))
                .thenReturn(response(operation("/api/v1/graph/{docId}", "GET", "arch-graph", "Arch Graph", null, null)));

        assertThat(lookup.exists("arch-graph", "Arch Graph", "GET", "/api/v1/graph/123/extra")).isFalse();
    }

    @Test
    void searchesUsingAStablePrefixSoATemplatedCatalogEntryCanBeFound() {
        when(productServiceClient.searchOperation(eq("api/v1/graph/123"), isNull())).thenReturn(emptyResponse());
        when(productServiceClient.searchOperation(eq("api/v1/graph"), isNull()))
                .thenReturn(response(operation("/api/v1/graph/{docId}", "POST", "fdmshowcaseapp", "FDM Showcase", null, null)));

        assertThat(lookup.exists("fdmshowcaseapp", "FDM Showcase", "POST", "/api/v1/graph/123")).isTrue();
    }

    @Test
    void matchesATemplateSegmentWhoseValueIsNeitherNumericNorAUuid() {
        when(productServiceClient.searchOperation(eq("api/v4/systems/BLN"), isNull())).thenReturn(emptyResponse());
        when(productServiceClient.searchOperation(eq("api/v4/systems"), isNull()))
                .thenReturn(response(operation("/api/v4/systems/{code}", "GET", "fdmshowcaseapp", "FDM Showcase", null, null)));

        assertThat(lookup.exists("fdmshowcaseapp", "FDM Showcase", "GET", "/api/v4/systems/BLN")).isTrue();
    }

    @Test
    void matchesATemplateSegmentInTheMiddleOfThePathWhoseValueIsNotNumericOrAUuid() {
        when(productServiceClient.searchOperation(eq("api/v4/systems/BLN/purpose"), isNull())).thenReturn(emptyResponse());
        when(productServiceClient.searchOperation(eq("api/v4/systems/BLN"), isNull())).thenReturn(emptyResponse());
        when(productServiceClient.searchOperation(eq("api/v4/systems"), isNull()))
                .thenReturn(response(operation("/api/v4/systems/{code}/purpose", "GET", "fdmshowcaseapp", "FDM Showcase", null, null)));

        assertThat(lookup.exists("fdmshowcaseapp", "FDM Showcase", "GET", "/api/v4/systems/BLN/purpose")).isTrue();
    }

    @Test
    void ignoresAQueryStringWhenMatchingAPlainOperation() {
        when(productServiceClient.searchOperation(eq("api/v4/systems?limit=10"), isNull())).thenReturn(emptyResponse());
        when(productServiceClient.searchOperation(eq("api/v4/systems"), isNull()))
                .thenReturn(response(operation("/api/v4/systems", "GET", "fdmshowcaseapp", "FDM Showcase", null, null)));

        assertThat(lookup.exists("fdmshowcaseapp", "FDM Showcase", "GET", "/api/v4/systems?limit=10")).isTrue();
    }

    @Test
    void ignoresAQueryStringGluedOntoATemplatedSegment() {
        when(productServiceClient.searchOperation(eq("api/v4/systems/123?full=true"), isNull())).thenReturn(emptyResponse());
        when(productServiceClient.searchOperation(eq("api/v4/systems/123"), isNull())).thenReturn(emptyResponse());
        when(productServiceClient.searchOperation(eq("api/v4/systems"), isNull()))
                .thenReturn(response(operation("/api/v4/systems/{code}", "GET", "fdmshowcaseapp", "FDM Showcase", null, null)));

        assertThat(lookup.exists("fdmshowcaseapp", "FDM Showcase", "GET", "/api/v4/systems/123?full=true")).isTrue();
    }

    @Test
    void searchesWithoutALeadingSlashSoACatalogEntryStoredWithoutOneCanBeFound() {
        when(productServiceClient.searchOperation(eq("getServiceList"), isNull()))
                .thenReturn(response(operation("getServiceList", "GET", "fdmshowcaseapp", "FDM Showcase", null, null)));

        assertThat(lookup.exists("fdmshowcaseapp", "FDM Showcase", "GET", "/getServiceList")).isTrue();
    }

    @Test
    void matchesByDisplayNameWhenTheOperationIsOwnedByADifferentCmdbRecordWithTheSameName() {
        OperationEntry entry = operation("getServiceList", "GET", "napiproxy.glassfish", "NAPIProxy", null, null);
        when(productServiceClient.searchOperation(anyString(), isNull())).thenReturn(response(entry));

        assertThat(lookup.exists("ext_napiproxy", "NAPIProxy", "GET", "getServiceList")).isTrue();
    }

    @Test
    void doesNotMatchByNameWhenNoNameIsSupplied() {
        OperationEntry entry = operation("getServiceList", "GET", "napiproxy.glassfish", "NAPIProxy", null, null);
        when(productServiceClient.searchOperation(anyString(), isNull())).thenReturn(response(entry));

        assertThat(lookup.exists("ext_napiproxy", null, "GET", "getServiceList")).isFalse();
    }

    @Test
    void acceptsAnUnknownTypedOperationOnlyWhenNoBetterCandidateExists() {
        OperationEntry entry = operation("getServiceList", "UNKNOWN", "napiproxy.glassfish", "NAPIProxy", null, null);
        when(productServiceClient.searchOperation(anyString(), isNull())).thenReturn(response(entry));

        assertThat(lookup.exists("napiproxy.glassfish", "NAPIProxy", "GET", "getServiceList")).isTrue();
    }

    @Test
    void rejectsAWrongTypedCandidateWhenNoUnknownTypedAlternativeExists() {
        OperationEntry wrongMethod = operation("getServiceList", "POST", "napiproxy.glassfish", "NAPIProxy", null, null);
        when(productServiceClient.searchOperation(anyString(), isNull())).thenReturn(response(wrongMethod));

        assertThat(lookup.exists("napiproxy.glassfish", "NAPIProxy", "GET", "getServiceList")).isFalse();
    }

    private static OperationSearchResponse response(OperationEntry entry) {
        OperationSearchResponse response = new OperationSearchResponse();
        response.setArchOperations(List.of(entry));
        response.setDiscoveredOperations(List.of());
        return response;
    }

    private static OperationSearchResponse emptyResponse() {
        OperationSearchResponse response = new OperationSearchResponse();
        response.setArchOperations(List.of());
        response.setDiscoveredOperations(List.of());
        return response;
    }

    private static OperationEntry operation(String path, String method, String productAlias, String productName,
                                             String containerCode, String containerName) {
        OperationEntry entry = new OperationEntry();
        entry.setName(path);
        entry.setType(method);
        if (productAlias != null || productName != null) {
            OperationEntry.ProductRef product = new OperationEntry.ProductRef();
            product.setAlias(productAlias);
            product.setName(productName);
            entry.setProduct(product);
        }
        if (containerCode != null || containerName != null) {
            OperationEntry.ContainerRef container = new OperationEntry.ContainerRef();
            container.setCode(containerCode);
            container.setName(containerName);
            entry.setContainer(container);
        }
        return entry;
    }
}
