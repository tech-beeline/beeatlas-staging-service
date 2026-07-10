package ru.beeline.staging.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Plain {@code new RestTemplate()} has no connect/read timeout at all — a hanging remote (product-service,
 * Structurizr) blocks the calling worker's scheduling thread indefinitely instead of failing and
 * letting Camunda retry, which is how an adapter task ends up stuck in "loading" for hours instead of
 * minutes. Timeout values match structurizr-sequence-extract.md's documented policy (60s on the
 * workspace.json request).
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }
}
