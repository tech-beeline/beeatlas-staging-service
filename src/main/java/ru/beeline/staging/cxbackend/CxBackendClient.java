package ru.beeline.staging.cxbackend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.beeline.staging.cxbackend.dto.CjTagsDto;
import ru.beeline.staging.cxbackend.dto.CjResponseDto;
import ru.beeline.staging.cxbackend.dto.ProcessCjDto;

import java.net.URI;

/**
 * Calls cx-backend's CJ library REST API to publish canonical e2e data into "наше
 * представление beatlas". BI/BiStep creation is delegated entirely to cx-backend's own
 * BPMN-import pipeline (see {@code CJimportFromBpmnService}), but skips the BPMN XML
 * intermediate representation entirely — we build the {@link ProcessCjDto} model directly and
 * POST it as JSON to {@code /product/cj/{id}/import-from-model}, since that's the model
 * cx-backend's importer consumes internally anyway — see
 * {@link ru.beeline.staging.pipeline.cxbackend.CxBackendCanonicalModelPublisher}.
 */
@Slf4j
@Component
public class CxBackendClient {

    private static final String USER_ID_HEADER = "user-id";

    private final RestTemplate restTemplate;
    private final String       baseUrl;

    public CxBackendClient(RestTemplate restTemplate,
                           @Value("${staging.cx-backend.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /** POST /api/cx/v1/product/{productId}/cj */
    public CjResponseDto createCj(Long productId, Long userId, CjTagsDto dto) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/api/cx/v1/product/{productId}/cj")
                .buildAndExpand(productId)
                .toUri();
        HttpEntity<CjTagsDto> entity = new HttpEntity<>(dto, userIdHeader(userId));
        log.debug("POST {} (createCj)", uri);
        return restTemplate.postForObject(uri, entity, CjResponseDto.class);
    }

    /** POST /api/cx/v1/product/cj/{id}/import-from-model */
    public void importFromModel(Long cjId, ProcessCjDto model, Long userId) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/api/cx/v1/product/cj/{id}/import-from-model")
                .buildAndExpand(cjId)
                .toUri();
        HttpHeaders headers = userIdHeader(userId);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        log.debug("POST {} (importFromModel, {} stage(s))", uri, model.getCollapsedSubProcesses().size());
        restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(model, headers), Void.class);
    }

    private HttpHeaders userIdHeader(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(USER_ID_HEADER, String.valueOf(userId));
        return headers;
    }
}
