/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.beeline.staging.product.dto.e2e.E2ePublishResponse;
import ru.beeline.staging.product.dto.e2e.E2eV2PublishRequest;

import java.nio.charset.StandardCharsets;

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

    public E2ePublishResponse upsertE2e(E2eV2PublishRequest request, Long relationId, Long pipelineRunId,
            String source) {
        String url = buildUrl(source);
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
                            + " url=" + url + " Last exception:\n" + e.getMessage(), e);
                }
                log.warn(
                        "fdm-products call failed for uid={}, relationId={}, pipelineRunId={} (attempt {}/{}), retrying in {}ms: {}",
                        uid, relationId, pipelineRunId, attempt, retryCount, retryDelayMs, e.getMessage());
                sleep(retryDelayMs);
            }
        }
    }

    private String buildUrl(String source) {
        String url = baseUrl + "/api/v2/e2e";
        if (source == null || source.isBlank()) {
            return url;
        }
        return UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("source", source.trim())
                .encode(StandardCharsets.UTF_8)
                .toUriString();
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
