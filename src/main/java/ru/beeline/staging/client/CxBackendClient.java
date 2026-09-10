/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.beeline.staging.product.dto.cx.BiStepContext;
import ru.beeline.staging.product.dto.cx.CxBiStepRelation;

import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
public class CxBackendClient {

    private static final String USER_ID_HEADER = "user-id";
    private static final String TECHNICAL_USER_ID = "0";

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CxBackendClient(RestTemplate restTemplate,
            @Value("${integration.cx-server-url:}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }

    public Optional<Integer> findBiStepIdByCode(String biStepCode) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/cx/v1/bi-step/{code}")
                .buildAndExpand(biStepCode)
                .encode()
                .toUriString();
        try {
            return Optional.ofNullable(restTemplate.getForObject(url, BiStepContext.class))
                    .map(BiStepContext::getBiStep)
                    .map(BiStepContext.BiStepRef::getId);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("cx-backend has no bi_step with code={} (url={})", biStepCode, url);
            return Optional.empty();
        }
    }

    public void replaceBiStepRelations(int biStepId, List<CxBiStepRelation> relations) {
        String url = baseUrl + "/api/cx/v1/library/business-interactions/step/" + biStepId + "/relation";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(USER_ID_HEADER, TECHNICAL_USER_ID);
        restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(relations, headers), Void.class);
        log.info("Replaced {} bi step relations in cx-backend: biStepId={}", relations.size(), biStepId);
    }
}
