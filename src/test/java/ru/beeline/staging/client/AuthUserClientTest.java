package ru.beeline.staging.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthUserClientTest {

    private static final String BASE_URL = "http://fdm-auth";

    private final RestTemplate restTemplate = mock(RestTemplate.class);

    @Test
    @DisplayName("Роль ADMINISTRATOR из fdm-auth распознаётся в любом регистре")
    void recognizesTheAdministratorRole() {
        when(restTemplate.getForObject(eq(BASE_URL + "/api/admin/v1/user/7/user-info"), eq(UserInfoDto.class)))
                .thenReturn(userInfo(List.of("PRODUCT_OWNER", "administrator")));

        assertThat(new AuthUserClient(restTemplate, BASE_URL).isAdministrator(7)).isTrue();
    }

    @Test
    @DisplayName("Без роли администратора — отказ")
    void rejectsAUserWithoutTheRole() {
        when(restTemplate.getForObject(anyString(), eq(UserInfoDto.class)))
                .thenReturn(userInfo(List.of("PRODUCT_OWNER")));

        assertThat(new AuthUserClient(restTemplate, BASE_URL).isAdministrator(7)).isFalse();
    }

    @Test
    @DisplayName("Пустые роли и пустой ответ не считаются администратором")
    void handlesEmptyAnswers() {
        AuthUserClient client = new AuthUserClient(restTemplate, BASE_URL);

        when(restTemplate.getForObject(anyString(), eq(UserInfoDto.class))).thenReturn(userInfo(null));
        assertThat(client.isAdministrator(7)).isFalse();

        when(restTemplate.getForObject(anyString(), eq(UserInfoDto.class))).thenReturn(null);
        assertThat(client.isAdministrator(7)).isFalse();
    }

    @Test
    @DisplayName("Недоступный fdm-auth не роняет запрос, а даёт отказ")
    void survivesAnUnavailableAuthService() {
        when(restTemplate.getForObject(anyString(), eq(UserInfoDto.class)))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThat(new AuthUserClient(restTemplate, BASE_URL).isAdministrator(7)).isFalse();
    }

    @Test
    @DisplayName("Без адреса сервиса и без userId запрос не уходит")
    void doesNotCallWithoutConfiguration() {
        assertThat(new AuthUserClient(restTemplate, "").isAdministrator(7)).isFalse();
        assertThat(new AuthUserClient(restTemplate, BASE_URL).isAdministrator(null)).isFalse();

        verifyNoInteractions(restTemplate);
    }

    private static UserInfoDto userInfo(List<String> roles) {
        UserInfoDto dto = new UserInfoDto();
        dto.setId(7);
        dto.setRoles(roles);
        return dto;
    }
}
