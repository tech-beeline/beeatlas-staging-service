/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class AuthUserClient {

    private static final String ADMINISTRATOR_ROLE = "ADMINISTRATOR";

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AuthUserClient(RestTemplate restTemplate,
                          @Value("${staging.auth-service.base-url:}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public boolean isAdministrator(Integer userId) {
        if (userId == null || baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String url = baseUrl + "/api/admin/v1/user/" + userId + "/user-info";
        try {
            UserInfoDto userInfo = restTemplate.getForObject(url, UserInfoDto.class);
            List<String> roles = userInfo != null ? userInfo.getRoles() : null;
            return roles != null && roles.stream()
                    .anyMatch(role -> ADMINISTRATOR_ROLE.equalsIgnoreCase(role.trim().toUpperCase(Locale.ROOT)));
        } catch (Exception e) {
            log.warn("Не удалось получить роли пользователя userId={} по {}: {}", userId, url, e.getMessage());
            return false;
        }
    }
}
