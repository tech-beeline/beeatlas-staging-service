/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant.Kind;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.product.dto.ContainerSummary;
import ru.beeline.staging.product.dto.ProductAliasSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves participant aliases against fdm-products: first as systems (batch, one call),
 * then unresolved aliases against the containers of the already-recognized systems (fdm-products
 * has no global container-by-alias search, only per-product listing).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FdmProductsCmdbAliasLookup implements CmdbAliasLookup {

    private final ProductServiceClient productServiceClient;

    @Override
    public Map<String, ResolvedParticipant> resolveAll(Set<String> aliases) {
        Map<String, ResolvedParticipant> result = new LinkedHashMap<>();
        if (aliases == null || aliases.isEmpty()) {
            return result;
        }

        List<ProductAliasSummary> products = productServiceClient.getByAliases(new ArrayList<>(aliases));
        Map<String, ProductAliasSummary> productsByLowerAlias = new LinkedHashMap<>();
        for (ProductAliasSummary product : products) {
            if (product.getAlias() != null) {
                productsByLowerAlias.putIfAbsent(product.getAlias().toLowerCase(Locale.ROOT), product);
            }
        }

        Set<String> remaining = new LinkedHashSet<>();
        Set<String> resolvedSystemAliases = new LinkedHashSet<>();
        for (String alias : aliases) {
            ProductAliasSummary match = productsByLowerAlias.get(alias.toLowerCase(Locale.ROOT));
            if (match != null) {
                result.put(alias, new ResolvedParticipant(alias, match.getName(), Kind.SYSTEM));
                resolvedSystemAliases.add(match.getAlias());
            } else {
                remaining.add(alias);
            }
        }

        for (String systemAlias : resolvedSystemAliases) {
            if (remaining.isEmpty()) {
                break;
            }
            List<ContainerSummary> containers = productServiceClient.getContainers(systemAlias);
            Map<String, ContainerSummary> containersByLowerCode = new LinkedHashMap<>();
            for (ContainerSummary container : containers) {
                if (container.getCode() != null) {
                    containersByLowerCode.putIfAbsent(container.getCode().toLowerCase(Locale.ROOT), container);
                }
            }
            remaining.removeIf(alias -> {
                ContainerSummary container = containersByLowerCode.get(alias.toLowerCase(Locale.ROOT));
                if (container == null) {
                    return false;
                }
                result.put(alias, new ResolvedParticipant(alias, container.getName(), Kind.CONTAINER));
                return true;
            });
        }

        log.info("CMDB alias resolution: total={} recognized={} unrecognized={}",
                aliases.size(), result.size(), aliases.size() - result.size());
        return result;
    }
}
