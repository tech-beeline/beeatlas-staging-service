package ru.beeline.staging.pipeline.preadapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.product.ProductServiceClient;
import ru.beeline.staging.product.dto.ProductSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans fdm-products (GET /api/v1/product/info) for products that carry a Structurizr workspace —
 * one found artifact per product, keyed by its alias. Appending "/json" to structurizrApiUrl is the
 * adapter's job (see StructurizrSequenceAdapter); the pre-adapter only resolves which products qualify.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructurizrSequencePreAdapter implements ArtifactPreAdapter {

    public static final String MODULE_CODE = "structurizr-sequence-preadapter";

    private final ProductServiceClient productServiceClient;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Scans fdm-products for products with a Structurizr workspace"; }

    @Override
    public List<FoundArtifact> scan(Configuration config) {
        List<ProductSummary> products = productServiceClient.listProducts();
        log.info("Product-service scan structurizr-sequence: found {} products for configId={}", products.size(), config.getId());

        List<FoundArtifact> found = new ArrayList<>();
        for (ProductSummary product : products) {
            if (product.getStructurizrApiUrl() == null || product.getStructurizrApiUrl().isBlank()) {
                continue;
            }
            if (product.getAlias() == null || product.getAlias().isBlank()) {
                log.warn("Skipping product id={} name={}: missing alias", product.getId(), product.getName());
                continue;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("productId", product.getId());
            metadata.put("productName", product.getName());
            metadata.put("structurizrApiUrl", product.getStructurizrApiUrl());
            metadata.put("structurizrWorkspaceName", product.getStructurizrWorkspaceName());
            found.add(new FoundArtifact(product.getAlias(), metadata));
        }
        return found;
    }
}
