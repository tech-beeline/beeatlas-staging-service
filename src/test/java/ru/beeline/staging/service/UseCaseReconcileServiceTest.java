package ru.beeline.staging.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.dto.usecase.ReconcileResponse;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeInterface;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeOperation;
import ru.beeline.staging.repository.UseCaseReconcileRepository;
import ru.beeline.staging.repository.UseCaseReconcileRepository.Candidate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UseCaseReconcileServiceTest {

    private UseCaseReconcileRepository reconcileRepository;
    private UseCaseLandscapeRepository landscapeRepository;
    private UseCaseReconcileService service;

    @BeforeEach
    void setUp() {
        reconcileRepository = mock(UseCaseReconcileRepository.class);
        landscapeRepository = mock(UseCaseLandscapeRepository.class);
        service = new UseCaseReconcileService(reconcileRepository, landscapeRepository);
    }

    @Test
    @DisplayName("Операция найдена в ветке требования — matched с версией операции")
    void matchesOperationInRequirementBranch() {
        when(reconcileRepository.findCandidates()).thenReturn(List.of(
                new Candidate(7L, "operation", "required", null, "design", "/pay", "pay_api", "pay")));
        when(landscapeRepository.findOperationByCode("/pay", "pay_api", "design"))
                .thenReturn(Optional.of(new LandscapeOperation(34L, "/pay", "pay_api", "pay", "BC-9")));
        when(reconcileRepository.markMatched(7L, 34L)).thenReturn(1);

        ReconcileResponse response = service.reconcile();

        verify(reconcileRepository).markMatched(7L, 34L);
        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.matched()).isEqualTo(1);
        assertThat(response.remainingRequired()).isZero();
    }

    @Test
    @DisplayName("Интерфейс сверяется по коду интерфейса и контейнера; не найден — остаётся required")
    void keepsMissingInterfaceRequired() {
        when(reconcileRepository.findCandidates()).thenReturn(List.of(
                new Candidate(8L, "interface", "required", null, "main", null, "pay_api", "pay.BC-9"),
                new Candidate(9L, "interface", "required", null, "main", null, "users_api", "gw.BC-1")));
        when(landscapeRepository.findInterface("users_api", "gw.BC-1", "main"))
                .thenReturn(Optional.of(new LandscapeInterface(50L, "users_api", "gw.BC-1", "BC-1")));
        when(reconcileRepository.markMatched(9L, null)).thenReturn(1);

        ReconcileResponse response = service.reconcile();

        verify(reconcileRepository, never()).markMatched(8L, null);
        assertThat(response.matched()).isEqualTo(1);
        assertThat(response.remainingRequired()).isEqualTo(1);
    }

    @Test
    @DisplayName("Повторная сверка без изменений не считает совпадение; исчезнувший matched не откатывается")
    void isIdempotentAndKeepsVanishedMatch() {
        when(reconcileRepository.findCandidates()).thenReturn(List.of(
                new Candidate(7L, "operation", "matched", 34L, "main", "/pay", "pay_api", "pay"),
                new Candidate(10L, "operation", "matched", 35L, "main", "/gone", "pay_api", "pay")));
        when(landscapeRepository.findOperationByCode("/pay", "pay_api", "main"))
                .thenReturn(Optional.of(new LandscapeOperation(34L, "/pay", "pay_api", "pay", "BC-9")));
        when(reconcileRepository.markMatched(7L, 34L)).thenReturn(0);

        ReconcileResponse response = service.reconcile();

        verify(reconcileRepository, never()).markMatched(org.mockito.ArgumentMatchers.eq(10L), any());
        assertThat(response.matched()).isZero();
        assertThat(response.remainingRequired()).isZero();
    }

    @Test
    @DisplayName("Плановых элементов нет — сверка завершается без изменений")
    void completesWithoutCandidates() {
        when(reconcileRepository.findCandidates()).thenReturn(List.of());

        ReconcileResponse response = service.reconcile();

        verify(reconcileRepository, never()).markMatched(anyLong(), any());
        assertThat(response.matched()).isZero();
        assertThat(response.remainingRequired()).isZero();
    }
}
