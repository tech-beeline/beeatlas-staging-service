package ru.beeline.staging.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import ru.beeline.staging.utils.JwtUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Получает короткоживущий Bearer-токен у ambassador'а по эндпоинту /rabbit-token.
 * Токен используется как пароль при подключении к RabbitMQ (вместо статического пароля).
 * Кэширует токен и обновляет его только при истечении (по полю exp в JWT-payload).
 */
@Slf4j
@Service
public class AuthSSOClient {

    private final RestTemplate restTemplate;
    private final String       serverUrl;

    public AuthSSOClient(@Value("${integration.authsso-server-url}") String serverUrl,
                         RestTemplate restTemplate) {
        this.serverUrl = serverUrl;
        this.restTemplate = restTemplate;
    }

    private static String          accessToken;
    private static ZonedDateTime   expiresAt;

    public String getToken() {
        if (accessToken == null || expiresAt.isBefore(ZonedDateTime.now(ZoneId.of("UTC")))) {
            accessToken = obtainAccessToken();
            expiresAt = Instant
                    .ofEpochSecond((Integer) JwtUtils.decodeJWT(accessToken).get("exp"))
                    .atZone(ZoneId.of("UTC"));
        }
        return accessToken;
    }

    private String obtainAccessToken() {
        log.info("Obtaining RabbitMQ SSO token from ambassador...");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<String> response = restTemplate.postForEntity(serverUrl, null, String.class);
        try {
            Map<String, Object> responseMap = new ObjectMapper().readValue(response.getBody(), Map.class);
            String token = responseMap.get("access_token").toString();
            log.info("Obtained RabbitMQ SSO token: ****{}", token.substring(Math.max(token.length() - 4, 0)));
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse ambassador token response", e);
        }
    }
}
