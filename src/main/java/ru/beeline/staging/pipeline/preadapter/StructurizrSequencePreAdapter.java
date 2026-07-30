package ru.beeline.staging.pipeline.preadapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.product.ProductServiceClient;
import ru.beeline.staging.product.dto.ProductSummary;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class StructurizrSequencePreAdapter implements ArtifactPreAdapter {

    public static final String MODULE_CODE = "structurizr-sequence-preadapter";

    private final ProductServiceClient productServiceClient;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Lists product mnemonics that have an architecture described in Structurizr (structurizrApiUrl set in fdm-products)"; }

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
        return found.stream()
                .sorted(Comparator.comparing(FoundArtifact::uid))
                .collect(Collectors.toUnmodifiableList());
    }
}
