package ru.beeline.staging.product;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import ru.beeline.staging.product.dto.ProductSummary;

import java.util.List;
import java.util.Optional;

/**
 * Talks to fdm-products' public product/info endpoints. GET /api/v1/product/info skips
 * HeaderInterceptor auth entirely (see documentation/fdm-products/api/HEADER_INTERCEPTOR.md), so no
 * user-id/user-roles headers are required for service-to-service calls.
 */
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
        ProductSummary[] products = restTemplate.getForObject(baseUrl + "/api/v1/product/info", ProductSummary[].class);
        return products != null ? List.of(products) : List.of();
    }

    /**
     * GET /api/v1/product/{code}/info — single product by alias. Used by the adapter stage, which
     * (per AdapterWorker) only ever receives the artifactUid, not the pre-adapter's FoundArtifact
     * metadata, so it re-resolves structurizrApiUrl itself instead of relying on a passed-through value.
     */
    public Optional<ProductSummary> getProductInfo(String alias) {
        try {
            return Optional.ofNullable(restTemplate.getForObject(baseUrl + "/api/v1/product/" + alias + "/info", ProductSummary.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
