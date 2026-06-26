package ru.beeline.staging.dashboard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Slf4j
@Component
public class DashboardClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public DashboardClient(RestTemplate restTemplate,
                           @Value("${staging.dashboard.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public String getScenarioSequence(String uid) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/api/v4/e2e/scenarios/{uid}/sequence")
                .buildAndExpand(uid)
                .toUri();
        log.debug("GET {}", uri);
        String result = restTemplate.getForObject(uri, String.class);
        log.debug("Received {} bytes for uid={}", result != null ? result.length() : 0, uid);
        return result;
    }
}
