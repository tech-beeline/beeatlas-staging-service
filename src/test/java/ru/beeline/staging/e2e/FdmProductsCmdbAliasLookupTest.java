package ru.beeline.staging.e2e;

import org.junit.jupiter.api.Test;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant.Kind;
import ru.beeline.staging.product.dto.ContainerByCodeSummary;
import ru.beeline.staging.product.dto.ProductAliasSummary;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FdmProductsCmdbAliasLookupTest {

    private final ProductServiceClient productServiceClient = mock(ProductServiceClient.class);
    private final FdmProductsCmdbAliasLookup lookup = new FdmProductsCmdbAliasLookup(productServiceClient);

    @Test
    void resolvesAContainerEvenWhenItsOwningSystemIsNotAmongTheRequestedAliases() {
        when(productServiceClient.getByAliases(anyList())).thenReturn(List.of());
        ContainerByCodeSummary container = new ContainerByCodeSummary();
        container.setName("Arch Graph Service");
        container.setCode("ext_container_arch_graph_service");
        container.setProductAlias("fdmshowcaseapp");
        when(productServiceClient.getContainersByCodes(anyList())).thenReturn(List.of(container));

        // the diagram references only the container — "fdmshowcaseapp" itself is never asked for
        Map<String, ResolvedParticipant> result = lookup.resolveAll(Set.of("ext_container_arch_graph_service"));

        assertThat(result).containsKey("ext_container_arch_graph_service");
        assertThat(result.get("ext_container_arch_graph_service").kind()).isEqualTo(Kind.CONTAINER);
        assertThat(result.get("ext_container_arch_graph_service").name()).isEqualTo("Arch Graph Service");
    }

    @Test
    void doesNotQueryContainersWhenEverythingAlreadyResolvedAsASystem() {
        ProductAliasSummary product = new ProductAliasSummary();
        product.setAlias("crm");
        product.setName("CRM System");
        when(productServiceClient.getByAliases(anyList())).thenReturn(List.of(product));

        Map<String, ResolvedParticipant> result = lookup.resolveAll(Set.of("crm"));

        assertThat(result.get("crm").kind()).isEqualTo(Kind.SYSTEM);
        verify(productServiceClient, never()).getContainersByCodes(anyList());
    }
}
