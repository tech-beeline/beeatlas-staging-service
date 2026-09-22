package ru.beeline.staging.e2e;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.product.dto.search.MatchedArchOperation;
import ru.beeline.staging.product.dto.search.OperationMatchCandidate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FdmProductsRestEndpointLookupTest {

    private final ProductServiceClient productServiceClient = mock(ProductServiceClient.class);
    private final FdmProductsRestEndpointLookup lookup = new FdmProductsRestEndpointLookup(productServiceClient);

    @Test
    void reportsAnEndpointThatTheLandscapeMatchingReturns() {
        when(productServiceClient.searchMatchedOperations(anyList()))
                .thenReturn(List.of(matched("/api/v1/product/{code}", "GET", "ext_product-api")));

        assertThat(lookup.exists("fdmshowcaseapp", "GET", "/api/v1/product/{code}")).isTrue();
    }

    @Test
    void reportsNoEndpointWhenTheLandscapeMatchingFindsNothing() {
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of());

        assertThat(lookup.exists("fdmshowcaseapp", "GET", "/api/v1/product/{cmdb}")).isFalse();
    }

    @Test
    void reportsNoEndpointWhenTheProductItselfIsUnknown() {
        MatchedArchOperation notFound = new MatchedArchOperation();
        notFound.setProductCode("ext_container_product");
        notFound.setNotFound(true);
        notFound.setError("Продукт с кодом ext_container_product не найден");
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of(notFound));

        assertThat(lookup.exists("ext_container_product", "GET", "/api/v1/product/{code}")).isFalse();
    }

    @Test
    void reportsNoEndpointWhenTheMatchCarriesNoInterfaceCode() {
        when(productServiceClient.searchMatchedOperations(anyList()))
                .thenReturn(List.of(matched("/api/v1/product/{code}", "GET", null)));

        assertThat(lookup.exists("fdmshowcaseapp", "GET", "/api/v1/product/{code}")).isFalse();
    }

    @Test
    void doesNotCallTheCatalogWhenTheParticipantHasNoProductAlias() {
        assertThat(lookup.exists(null, "GET", "/api/v1/product/{code}")).isFalse();
        assertThat(lookup.exists(" ", "GET", "/api/v1/product/{code}")).isFalse();

        verifyNoInteractions(productServiceClient);
    }

    @Test
    void sendsTheCallAsASingleCandidateWithoutRewritingThePath() {
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of());

        lookup.exists("fdmshowcaseapp", "PATCH", "/api/v1/product/{code}/workspace?force=true");

        ArgumentCaptor<List<OperationMatchCandidate>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(productServiceClient).searchMatchedOperations(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(candidate -> {
            assertThat(candidate.getProductCode()).isEqualTo("fdmshowcaseapp");
            assertThat(candidate.getMethodType()).isEqualTo("PATCH");
            assertThat(candidate.getMethodName()).isEqualTo("/api/v1/product/{code}/workspace?force=true");
            assertThat(candidate.getProtocol()).isNull();
        });
    }

    private static MatchedArchOperation matched(String name, String type, String interfaceCode) {
        MatchedArchOperation match = new MatchedArchOperation();
        match.setName(name);
        match.setType(type);
        match.setProductCode("fdmshowcaseapp");
        MatchedArchOperation.Ref interfaceRef = new MatchedArchOperation.Ref();
        interfaceRef.setCode(interfaceCode);
        interfaceRef.setName("API получения информации о продуктах");
        match.setInterfaceObj(interfaceRef);
        return match;
    }
}
