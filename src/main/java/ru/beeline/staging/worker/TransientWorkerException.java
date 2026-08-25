package ru.beeline.staging.worker;

/**
 * Marker exception that wraps a transient worker failure and carries
 * retry metadata (retries remaining, backoff) so that concrete workers
 * can annotate their stage-logs with retry observability data.
 */
public class TransientWorkerException extends RuntimeException {

    private final int retriesLeft;
    private final long backoffMs;

    public TransientWorkerException(String message, Throwable cause, int retriesLeft, long backoffMs) {
        super(message, cause);
        this.retriesLeft = retriesLeft;
        this.backoffMs = backoffMs;
    }

    public int getRetriesLeft() {
        return retriesLeft;
    }

    public long getBackoffMs() {
        return backoffMs;
    }
}
