/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.beeline.staging.product.dto.ContainerByCodeSummary;
import ru.beeline.staging.product.dto.OperationSearchResponse;
import ru.beeline.staging.product.dto.ProductAliasSummary;
import ru.beeline.staging.product.dto.ProductSummary;
import ru.beeline.staging.product.dto.search.MatchedArchOperation;
import ru.beeline.staging.product.dto.search.OperationMatchCandidate;

import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
public class ProductServiceClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ProductServiceClient(RestTemplate restTemplate,
                                 @Value("${integration.product-server-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public List<ProductSummary> listProducts() {
        String url = baseUrl + "/api/v1/product/info";
        log.info("Fetching product list: url={}", url);
        try {
            ProductSummary[] products = restTemplate.getForObject(url, ProductSummary[].class);
            return products != null ? List.of(products) : List.of();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch product list: url=" + url + " — " + e.getMessage(), e);
        }
    }

    public Optional<ProductSummary> getProductInfo(String alias) {
        String url = baseUrl + "/api/v1/product/" + alias + "/info";
        log.info("Fetching product info: alias={} url={}", alias, url);
        try {
            return Optional.ofNullable(restTemplate.getForObject(url, ProductSummary.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch product info: alias=" + alias + " url=" + url + " — " + e.getMessage(), e);
        }
    }

    /**
     * Batch lookup of products (systems) by CMDB alias. Aliases are matched case-insensitively
     * by fdm-products; only found products are returned (GET /api/v1/product/by-aliases).
     */
    public List<ProductAliasSummary> getByAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v1/product/by-aliases")
                .queryParam("aliases", aliases)
                .toUriString();
        log.info("Fetching products by aliases: count={} url={}", aliases.size(), url);
        try {
            ProductAliasSummary[] products = restTemplate.getForObject(url, ProductAliasSummary[].class);
            return products != null ? List.of(products) : List.of();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch products by aliases: url=" + url + " — " + e.getMessage(), e);
        }
    }

    /**
     * Global container lookup by CMDB code (GET /api/v1/container/by-codes) — unlike a per-product
     * listing, the caller does not need to already know/have resolved the owning system.
     */
    public List<ContainerByCodeSummary> getContainersByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v1/container/by-codes")
                .queryParam("codes", codes)
                .toUriString();
        log.info("Fetching containers by codes: count={} url={}", codes.size(), url);
        try {
            ContainerByCodeSummary[] containers = restTemplate.getForObject(url, ContainerByCodeSummary[].class);
            return containers != null ? List.of(containers) : List.of();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch containers by codes: url=" + url + " — " + e.getMessage(), e);
        }
    }

    /**
     * Batch match of diagram calls against architecture operations (POST /api/v1/operation/search-matched).
     * The response is flat: candidates without a match simply contribute nothing, so callers re-associate
     * entries by productCode + operation name/type rather than by position.
     */
    public List<MatchedArchOperation> searchMatchedOperations(List<OperationMatchCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + "/api/v1/operation/search-matched";
        log.info("Searching matched operations: candidates={} url={}", candidates.size(), url);
        try {
            MatchedArchOperation[] matched = restTemplate.postForObject(url, candidates, MatchedArchOperation[].class);
            return matched != null ? List.of(matched) : List.of();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to search matched operations: url=" + url
                    + " candidates=" + candidates.size() + " — " + e.getMessage(), e);
        }
    }

    /**
     * Operation search by path fragment and HTTP method (GET /api/v1/operation). fdm-products matches
     * {@code path} with a substring ILIKE — callers must re-filter the result for an exact path match.
     */
    public OperationSearchResponse searchOperation(String path, String type) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v1/operation")
                .queryParam("path", path)
                .queryParamIfPresent("type", Optional.ofNullable(type))
                .toUriString();
        log.info("Searching operation: path={} type={} url={}", path, type, url);
        try {
            OperationSearchResponse response = restTemplate.getForObject(url, OperationSearchResponse.class);
            return response != null ? response : new OperationSearchResponse();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to search operation: path=" + path + " url=" + url + " — " + e.getMessage(), e);
        }
    }
}
