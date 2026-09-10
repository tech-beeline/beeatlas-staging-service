/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;
import ru.beeline.staging.dto.pipelinerun.PipelineRunStatusResponse;
import ru.beeline.staging.dto.pipelinerun.PipelineRunStatusSnapshot;
import ru.beeline.staging.repository.PipelineRunStatusRepository;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class PipelineRunStatusService {

    public static final String WAIT_FOR_TERMINAL = "terminal";

    private static final Set<String> WAIT_FOR_VALUES = Set.of("awaiting_review", "completed", WAIT_FOR_TERMINAL);
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "failed", "cancelled");
    private static final long DEFERRED_RESULT_GRACE_MS = 1000;

    private final PipelineRunStatusRepository statusRepository;
    private final ScheduledExecutorService poller;
    private final long defaultTimeoutMs;
    private final long maxTimeoutMs;
    private final long pollIntervalMs;

    public PipelineRunStatusService(
            PipelineRunStatusRepository statusRepository,
            @Value("${staging.run-status.default-timeout-ms:30000}") long defaultTimeoutMs,
            @Value("${staging.run-status.max-timeout-ms:60000}") long maxTimeoutMs,
            @Value("${staging.run-status.poll-interval-ms:500}") long pollIntervalMs,
            @Value("${staging.run-status.poller-threads:2}") int pollerThreads) {
        this.statusRepository = statusRepository;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.maxTimeoutMs = maxTimeoutMs;
        this.pollIntervalMs = pollIntervalMs;
        this.poller = Executors.newScheduledThreadPool(pollerThreads, runnable -> {
            Thread thread = new Thread(runnable, "staging-runstatus-");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
    }

    public DeferredResult<ResponseEntity<Object>> watch(Long runId, String waitFor, Integer timeoutMs) {
        String target = waitFor == null || waitFor.isBlank() ? WAIT_FOR_TERMINAL : waitFor.trim().toLowerCase();
        if (!WAIT_FOR_VALUES.contains(target)) {
            return immediately(badRequest("Недопустимое значение waitFor: " + waitFor
                    + ". Допустимые значения: awaiting_review, completed, " + WAIT_FOR_TERMINAL));
        }
        if (timeoutMs != null && (timeoutMs <= 0 || timeoutMs > maxTimeoutMs)) {
            return immediately(badRequest("timeoutMs должен быть целым числом от 1 до " + maxTimeoutMs));
        }
        long timeout = timeoutMs != null ? timeoutMs : defaultTimeoutMs;

        Optional<PipelineRunStatusSnapshot> snapshot = statusRepository.findSnapshot(runId);
        if (snapshot.isEmpty()) {
            return immediately(notFound(runId));
        }
        if (waitingIsOver(snapshot.get().status(), target)) {
            return immediately(ok(snapshot.get(), false));
        }

        DeferredResult<ResponseEntity<Object>> deferred =
                new DeferredResult<>(timeout + DEFERRED_RESULT_GRACE_MS);
        deferred.onTimeout(() -> deferred.setResult(respond(runId, true)));
        schedulePolling(deferred, runId, target, timeout);
        return deferred;
    }

    private void schedulePolling(DeferredResult<ResponseEntity<Object>> deferred, Long runId,
                                  String target, long timeoutMs) {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        AtomicReference<ScheduledFuture<?>> handle = new AtomicReference<>();

        handle.set(poller.scheduleWithFixedDelay(() -> {
            if (deferred.isSetOrExpired()) {
                cancel(handle);
                return;
            }
            try {
                Optional<String> status = statusRepository.findStatus(runId);
                if (status.isEmpty()) {
                    deferred.setResult(notFound(runId));
                    cancel(handle);
                    return;
                }
                boolean waitingIsOver = waitingIsOver(status.get(), target);
                if (waitingIsOver || System.nanoTime() - deadline >= 0) {
                    deferred.setResult(respond(runId, !waitingIsOver));
                    cancel(handle);
                }
            } catch (RuntimeException e) {
                log.warn("Не удалось прочитать статус запуска {} во время ожидания", runId, e);
                deferred.setErrorResult(e);
                cancel(handle);
            }
        }, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS));

        deferred.onCompletion(() -> cancel(handle));
    }

    private ResponseEntity<Object> respond(Long runId, boolean more) {
        return statusRepository.findSnapshot(runId)
                .map(snapshot -> ok(snapshot, more))
                .orElseGet(() -> notFound(runId));
    }

    private ResponseEntity<Object> ok(PipelineRunStatusSnapshot snapshot, boolean more) {
        return ResponseEntity.ok(PipelineRunStatusResponse.of(snapshot, null, more));
    }

    private boolean waitingIsOver(String status, String target) {
        if (status == null) {
            return false;
        }
        return TERMINAL_STATUSES.contains(status) || target.equals(status);
    }

    private static void cancel(AtomicReference<ScheduledFuture<?>> handle) {
        ScheduledFuture<?> future = handle.get();
        if (future != null) {
            future.cancel(false);
        }
    }

    private static DeferredResult<ResponseEntity<Object>> immediately(ResponseEntity<Object> response) {
        DeferredResult<ResponseEntity<Object>> deferred = new DeferredResult<>();
        deferred.setResult(response);
        return deferred;
    }

    private static ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static ResponseEntity<Object> notFound(Long runId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Pipeline run not found");
        body.put("runId", runId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
