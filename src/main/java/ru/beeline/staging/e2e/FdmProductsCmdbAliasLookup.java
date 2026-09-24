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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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

        List<String> keys = new ArrayList<>(aliases);
        Map<String, ProductAliasSummary> productsByLowerAlias =
                byLowerKey(productServiceClient.getByAliases(keys), ProductAliasSummary::getAlias);
        Map<String, ContainerByCodeSummary> containersByLowerCode =
                byLowerKey(productServiceClient.getContainersByCodes(keys), ContainerByCodeSummary::getCode);

        for (String alias : aliases) {
            String key = alias.toLowerCase(Locale.ROOT);
            ProductAliasSummary product = productsByLowerAlias.get(key);
            ContainerByCodeSummary container = containersByLowerCode.get(key);
            if (product != null) {
                result.put(alias, new ResolvedParticipant(alias, product.getName(), Kind.SYSTEM));
            } else if (container != null) {
                result.put(alias, asParticipant(alias, container));
            }
        }

        log.info("CMDB alias resolution: total={} recognized={} unrecognized={}",
                aliases.size(), result.size(), aliases.size() - result.size());
        return result;
    }

    private static ResolvedParticipant asParticipant(String alias, ContainerByCodeSummary container) {
        return new ResolvedParticipant(alias, container.getName(), Kind.CONTAINER, container.getProductAlias());
    }

    private static <T> Map<String, T> byLowerKey(List<T> items, Function<T, String> keyOf) {
        Map<String, T> byKey = new LinkedHashMap<>();
        for (T item : items) {
            String key = keyOf.apply(item);
            if (key != null) {
                byKey.putIfAbsent(key.toLowerCase(Locale.ROOT), item);
            }
        }
        return byKey;
    }
}
