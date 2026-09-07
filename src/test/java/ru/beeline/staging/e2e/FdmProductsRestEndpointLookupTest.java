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
        // first try (the full path as needle) finds nothing — it has to fall back to a shorter one
        when(productServiceClient.searchOperation(eq("api/v1/graph/123"), isNull())).thenReturn(emptyResponse());
        when(productServiceClient.searchOperation(eq("api/v1/graph"), isNull()))
                .thenReturn(response(operation("/api/v1/graph/{docId}", "POST", "fdmshowcaseapp", "FDM Showcase", null, null)));

        assertThat(lookup.exists("fdmshowcaseapp", "FDM Showcase", "POST", "/api/v1/graph/123")).isTrue();
    }

    /**
     * Real func-stand finding: a template value that isn't a number or a UUID — a system mnemonic
     * ("BLN") or a process uid ("abc-123") — used to poison the search needle (the old heuristic only
     * recognized digit/UUID segments as "variable", so it kept the literal value in the needle, which
     * is never a substring of the stored "{code}"/"{uid}" template). The progressive-shrink search
     * doesn't care what the value looks like.
     */
    @Test
    void matchesATemplateSegmentWhoseValueIsNeitherNumericNorAUuid() {
        when(productServiceClient.searchOperation(eq("api/v4/systems/BLN"), isNull())).thenReturn(emptyResponse());
        when(productServiceClient.searchOperation(eq("api/v4/systems"), isNull()))
                .thenReturn(response(operation("/api/v4/systems/{code}", "GET", "fdmshowcaseapp", "FDM Showcase", null, null)));

        assertThat(lookup.exists("fdmshowcaseapp", "FDM Showcase", "GET", "/api/v4/systems/BLN")).isTrue();
    }

    /** Same real func-stand finding, with the template in the middle of the path rather than at the end. */
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
        // real dev CMDB, checked by hand: op id 61992 is stored as "getServiceList", no leading slash
        // at all — a needle that keeps the diagram's "/" would never be a substring of it
        when(productServiceClient.searchOperation(eq("getServiceList"), isNull()))
                .thenReturn(response(operation("getServiceList", "GET", "fdmshowcaseapp", "FDM Showcase", null, null)));

        assertThat(lookup.exists("fdmshowcaseapp", "FDM Showcase", "GET", "/getServiceList")).isTrue();
    }

    /**
     * CMDB data-quality gap (real dev finding, id 61992): the same real-world "NapiProxy" system
     * is represented by several disconnected records — the receiver resolved to container
     * "ext_napiproxy" (by code), but the operation is registered under an unrelated product
     * "napiproxy.glassfish" that merely shares the display name "NAPIProxy". A code match fails,
     * but the name match must still find it.
     */
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
        // the UNKNOWN fallback must stay narrow: a candidate with an explicit, different method is
        // still a rejection, not a free pass, when nothing UNKNOWN-typed is present to fall back to
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
