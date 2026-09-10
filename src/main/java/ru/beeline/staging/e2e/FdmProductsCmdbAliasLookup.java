/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant.Kind;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.product.dto.ContainerByCodeSummary;
import ru.beeline.staging.product.dto.ProductAliasSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        for (String alias : aliases) {
            ProductAliasSummary match = productsByLowerAlias.get(alias.toLowerCase(Locale.ROOT));
            if (match != null) {
                result.put(alias, new ResolvedParticipant(alias, match.getName(), Kind.SYSTEM));
            } else {
                remaining.add(alias);
            }
        }

        if (!remaining.isEmpty()) {
            List<ContainerByCodeSummary> containers = productServiceClient.getContainersByCodes(new ArrayList<>(remaining));
            Map<String, ContainerByCodeSummary> containersByLowerCode = new LinkedHashMap<>();
            for (ContainerByCodeSummary container : containers) {
                if (container.getCode() != null) {
                    containersByLowerCode.putIfAbsent(container.getCode().toLowerCase(Locale.ROOT), container);
                }
            }
            for (String alias : remaining) {
                ContainerByCodeSummary container = containersByLowerCode.get(alias.toLowerCase(Locale.ROOT));
                if (container != null) {
                    result.put(alias, new ResolvedParticipant(alias, container.getName(), Kind.CONTAINER,
                            container.getProductAlias()));
                }
            }
        }

        log.info("CMDB alias resolution: total={} recognized={} unrecognized={}",
                aliases.size(), result.size(), aliases.size() - result.size());
        return result;
    }
}
