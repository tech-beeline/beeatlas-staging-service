/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.beeline.staging.product.dto.ContainerSummary;
import ru.beeline.staging.product.dto.OperationSearchResponse;
import ru.beeline.staging.product.dto.ProductAliasSummary;
import ru.beeline.staging.product.dto.ProductSummary;

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
     * Containers of a product (system), CMDB alias in {@code code} (GET /api/v1/product/{cmdb}/container).
     */
    public List<ContainerSummary> getContainers(String cmdb) {
        String url = baseUrl + "/api/v1/product/" + cmdb + "/container";
        log.info("Fetching containers: cmdb={} url={}", cmdb, url);
        try {
            ContainerSummary[] containers = restTemplate.getForObject(url, ContainerSummary[].class);
            return containers != null ? List.of(containers) : List.of();
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch containers: cmdb=" + cmdb + " url=" + url + " — " + e.getMessage(), e);
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
