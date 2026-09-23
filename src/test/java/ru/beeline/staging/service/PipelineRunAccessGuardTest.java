package ru.beeline.staging.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.client.AuthUserClient;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.exception.PipelineRunForbiddenException;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunAccessGuardTest {

    private static final long RUN_ID = 77L;

    private PipelineRunRepository pipelineRunRepository;
    private AuthUserClient authUserClient;
    private PipelineRunAccessGuard guard;

    @BeforeEach
    void setUp() {
        pipelineRunRepository = mock(PipelineRunRepository.class);
        authUserClient = mock(AuthUserClient.class);
        guard = new PipelineRunAccessGuard(pipelineRunRepository, authUserClient);
    }

    @Test
    @DisplayName("Автор запуска принимает решения без обращения к fdm-auth")
    void authorDecidesWithoutAskingAuth() {
        givenRunWithAuthor(434318);

        assertThatCode(() -> guard.requireAuthor(RUN_ID, 434318)).doesNotThrowAnyException();

        verify(authUserClient, never()).isAdministrator(any());
    }

    @Test
    @DisplayName("Прогон без автора доступен любому")
    void runWithoutAnAuthorIsOpen() {
        givenRunWithAuthor(null);

        assertThatCode(() -> guard.requireAuthor(RUN_ID, 1)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireDecisionRights(RUN_ID, 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Администратор не принимает решения за автора — постановка SFDM-4160")
    void administratorMayNotDecideForTheAuthor() {
        givenRunWithAuthor(434318);
        when(authUserClient.isAdministrator(7)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireAuthor(RUN_ID, 7))
                .isInstanceOf(PipelineRunForbiddenException.class)
                .hasMessageContaining("только пользователь, который его запустил");
        verify(authUserClient, never()).isAdministrator(any());
    }

    @Test
    @DisplayName("Отменить чужой запуск администратор по-прежнему может")
    void administratorMayCancelForeignRun() {
        givenRunWithAuthor(434318);
        when(authUserClient.isAdministrator(7)).thenReturn(true);

        assertThatCode(() -> guard.requireDecisionRights(RUN_ID, 7)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Чужой пользователь получает отказ")
    void otherUserIsRejected() {
        givenRunWithAuthor(434318);
        when(authUserClient.isAdministrator(7)).thenReturn(false);

        assertThatThrownBy(() -> guard.requireAuthor(RUN_ID, 7))
                .isInstanceOf(PipelineRunForbiddenException.class)
                .hasMessageContaining("только пользователь, который его запустил");
    }

    @Test
    @DisplayName("Отсутствие user-id у прогона с автором тоже отказ")
    void missingUserIdIsRejected() {
        givenRunWithAuthor(434318);

        assertThatThrownBy(() -> guard.requireAuthor(RUN_ID, null))
                .isInstanceOf(PipelineRunForbiddenException.class);
    }

    private void givenRunWithAuthor(Integer author) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setCreatedByUserId(author);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }
}
