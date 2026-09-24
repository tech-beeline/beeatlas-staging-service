package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;
import ru.beeline.staging.dto.pipelinerun.PipelineRunStatusResponse;
import ru.beeline.staging.dto.pipelinerun.PipelineRunStatusSnapshot;
import ru.beeline.staging.repository.PipelineRunStatusRepository;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineRunStatusServiceTest {

    private static final long RUN_ID = 1644367L;

    private PipelineRunStatusRepository statusRepository;
    private PipelineRunStatusService service;

    @BeforeEach
    void setUp() {
        statusRepository = mock(PipelineRunStatusRepository.class);
        service = new PipelineRunStatusService(statusRepository, 300, 60000, 20, 1);
    }

    @Test
    @DisplayName("Недопустимый waitFor — 400")
    void rejectsUnknownWaitFor() {
        ResponseEntity<Object> response = result(service.watch(RUN_ID, "whenever", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response)).containsEntry("error",
                "Недопустимое значение waitFor: whenever. Допустимые значения: awaiting_review, completed, terminal");
    }

    @Test
    @DisplayName("timeoutMs вне допустимого диапазона — 400")
    void rejectsOutOfRangeTimeout() {
        assertThat(result(service.watch(RUN_ID, null, 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result(service.watch(RUN_ID, null, 60001)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Несуществующий запуск — 404")
    void answersNotFound() {
        when(statusRepository.findSnapshot(RUN_ID)).thenReturn(Optional.empty());

        ResponseEntity<Object> response = result(service.watch(RUN_ID, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(response)).containsEntry("runId", RUN_ID);
    }

    @Test
    @DisplayName("Терминальный статус отдаётся сразу с more=false")
    void answersImmediatelyForTerminalStatus() {
        when(statusRepository.findSnapshot(RUN_ID)).thenReturn(Optional.of(snapshot("completed")));

        PipelineRunStatusResponse response = status(service.watch(RUN_ID, null, null));

        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.stage()).isEqualTo("saver");
        assertThat(response.noticesCount()).isEqualTo(3);
        assertThat(response.more()).isFalse();
    }

    @Test
    @DisplayName("Целевой статус waitFor отдаётся сразу с more=false")
    void answersImmediatelyForTargetStatus() {
        when(statusRepository.findSnapshot(RUN_ID)).thenReturn(Optional.of(snapshot("awaiting_review")));

        PipelineRunStatusResponse response = status(service.watch(RUN_ID, "awaiting_review", null));

        assertThat(response.more()).isFalse();
    }

    @Test
    @DisplayName("Запуск дошёл до терминального статуса во время ожидания — more=false")
    void completesWhileWaiting() {
        when(statusRepository.findSnapshot(RUN_ID))
                .thenReturn(Optional.of(snapshot("transforming")), Optional.of(snapshot("completed")));
        when(statusRepository.findStatus(RUN_ID)).thenReturn(Optional.of("completed"));

        PipelineRunStatusResponse response = status(service.watch(RUN_ID, null, null));

        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.more()).isFalse();
    }

    @Test
    @DisplayName("Истёк timeoutMs — текущий статус с more=true")
    void answersMoreOnTimeout() {
        when(statusRepository.findSnapshot(RUN_ID)).thenReturn(Optional.of(snapshot("transforming")));
        when(statusRepository.findStatus(RUN_ID)).thenReturn(Optional.of("transforming"));

        PipelineRunStatusResponse response = status(service.watch(RUN_ID, "completed", 100));

        assertThat(response.status()).isEqualTo("transforming");
        assertThat(response.more()).isTrue();
    }

    @Test
    @DisplayName("Запуск исчез во время ожидания — 404")
    void answersNotFoundWhenRunDisappears() {
        when(statusRepository.findSnapshot(RUN_ID)).thenReturn(Optional.of(snapshot("transforming")));
        when(statusRepository.findStatus(RUN_ID)).thenReturn(Optional.empty());

        assertThat(result(service.watch(RUN_ID, null, null)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Пауза достигнута во время ожидания — result из снапшота в ответе")
    void returnsResultWhenPauseReachedWhileWaiting() {
        ObjectNode draft = JsonNodeFactory.instance.objectNode();
        draft.putObject("usecase").put("code", "UC-001");
        draft.putArray("unmapped").addObject().put("partId", "P-04");
        when(statusRepository.findSnapshot(RUN_ID)).thenReturn(
                Optional.of(snapshot("transforming")),
                Optional.of(new PipelineRunStatusSnapshot(RUN_ID, "usecase", "UC-001", "awaiting_review",
                        "transformer", 0, draft)));
        when(statusRepository.findStatus(RUN_ID)).thenReturn(Optional.of("awaiting_review"));

        PipelineRunStatusResponse response = status(service.watch(RUN_ID, "awaiting_review", null));

        assertThat(response.status()).isEqualTo("awaiting_review");
        assertThat(response.more()).isFalse();
        assertThat(response.result()).isEqualTo(draft);
    }

    private PipelineRunStatusSnapshot snapshot(String status) {
        return new PipelineRunStatusSnapshot(RUN_ID, "e2e-plantuml", "E2E-001", status, "saver", 3, null);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Object> result(DeferredResult<ResponseEntity<Object>> deferred) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!deferred.hasResult() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(deferred.hasResult()).as("ответ не сформирован за 5 секунд").isTrue();
        return (ResponseEntity<Object>) deferred.getResult();
    }

    private PipelineRunStatusResponse status(DeferredResult<ResponseEntity<Object>> deferred) {
        ResponseEntity<Object> response = result(deferred);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (PipelineRunStatusResponse) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Object> response) {
        return (Map<String, Object>) response.getBody();
    }
}
