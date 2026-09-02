/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * S2S-чтение содержимого документа из document-service по числовому id (STG-02/DOC-08).
 * Использует уже существующий общий {@code GET /api/v1/documents/{id}} — независимо от того,
 * зарегистрирован ли в document-service отдельный тип документа для PlantUML e2e (DOC-01).
 */
@Slf4j
@Component
public class DocumentServiceClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public DocumentServiceClient(RestTemplate restTemplate,
                                  @Value("${staging.document-service.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String fetchContent(long docId) {
        String url = baseUrl + "/api/v1/documents/" + docId;
        log.info("Fetching document content: docId={} url={}", docId, url);
        try {
            byte[] body = restTemplate.getForObject(url, byte[].class);
            if (body == null) {
                throw new DocumentNotFoundException(docId);
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (HttpClientErrorException.NotFound e) {
            throw new DocumentNotFoundException(docId);
        } catch (HttpClientErrorException.BadRequest e) {
            throw new DocumentNotFoundException(docId);
        } catch (RestClientException e) {
            HttpStatusCode status = (e instanceof HttpClientErrorException httpError) ? httpError.getStatusCode() : null;
            throw new DocumentServiceUnavailableException(
                    "Failed to fetch document content: docId=" + docId + " url=" + url
                            + (status != null ? " status=" + status : "") + " — " + e.getMessage(), e);
        }
    }
}
