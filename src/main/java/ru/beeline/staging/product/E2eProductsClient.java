package ru.beeline.staging.product;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import ru.beeline.staging.product.dto.e2e.E2ePublishResponse;
import ru.beeline.staging.product.dto.e2e.E2eV2PublishRequest;

@Slf4j
@Repository
public class E2eProductsClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final int retryCount;
    private final long retryDelayMs;

    public E2eProductsClient(RestTemplate restTemplate,
                              @Value("${integration.product-server-url}") String baseUrl,
                              @Value("${integration.e2e-publish.retry-count:3}") int retryCount,
                              @Value("${integration.e2e-publish.retry-delay-ms:1000}") long retryDelayMs) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.retryCount = retryCount;
        this.retryDelayMs = retryDelayMs;
    }

    /**
     * POST /api/v2/e2e — Sparx-sourced e2e ingested directly into the product catalog
     * (discovered_interface/discovered_operation), no containers layer. Retries on 5xx/connection
     * failures up to retryCount; 409 is logged and swallowed (version conflicts are fdm-products'
     * concern); any other 4xx is fatal.
     */
    public E2ePublishResponse upsertE2e(E2eV2PublishRequest request, Long relationId, Long pipelineRunId) {
        String url = baseUrl + "/api/v2/e2e";
        String uid = request.getE2e() != null ? request.getE2e().getUid() : null;

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                E2ePublishResponse response = restTemplate.postForObject(url, request, E2ePublishResponse.class);
                log.info("Published e2e uid={}, relationId={}, pipelineRunId={} to fdm-products: response={}",
                        uid, relationId, pipelineRunId, response);
                return response;
            } catch (HttpClientErrorException.Conflict e) {
                log.warn("fdm-products reported a version conflict for e2e uid={}, relationId={}, pipelineRunId={}: {}",
                        uid, relationId, pipelineRunId, e.getMessage());
                return null;
            } catch (HttpClientErrorException e) {
                throw new IllegalStateException("fdm-products rejected e2e publish request: uid=" + uid
                        + " relationId=" + relationId + " pipelineRunId=" + pipelineRunId
                        + " url=" + url + " status=" + e.getStatusCode() + " body=" + e.getResponseBodyAsString(), e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                if (attempt > retryCount) {
                    throw new IllegalStateException("fdm-products unreachable after " + retryCount
                            + " retries: uid=" + uid + " relationId=" + relationId + " pipelineRunId=" + pipelineRunId
                            + " url=" + url, e);
                }
                log.warn("fdm-products call failed for uid={}, relationId={}, pipelineRunId={} (attempt {}/{}), retrying in {}ms: {}",
                        uid, relationId, pipelineRunId, attempt, retryCount, retryDelayMs, e.getMessage());
                sleep(retryDelayMs);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying fdm-products call", e);
        }
    }
}
