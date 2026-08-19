package ru.beeline.staging.worker;

import org.springframework.stereotype.Component;

/**
 * Classifies worker exceptions into transient (retriable) and permanent (non-retriable).
 * <p>
 * Transient errors are infrastructure-level failures (network, DB, 5xx) that may resolve
 * on retry. Permanent errors are data/configuration problems (4xx, missing modules, bad
 * data) that will not recover on retry.
 * </p>
 */
@Component
public class WorkerErrorClassifier {

    /**
     * Returns {@code true} if the exception is considered transient and should be retried.
     * Walks the cause chain (e) to find a known transient root cause.
     */
    public boolean isTransient(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (isTransientCause(cause)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTransientCause(Throwable t) {
        String cls = t.getClass().getName();

        // Spring Web — network / timeout
        if (cls.equals("org.springframework.web.client.ResourceAccessException")) {
            return true;
        }
        // Spring Web — 5xx
        if (cls.equals("org.springframework.web.client.HttpServerErrorException")) {
            return true;
        }
        // Standard Java I/O — timeouts, connection refused, etc.
        if (cls.equals("java.net.SocketTimeoutException")
                || cls.equals("java.net.ConnectException")
                || cls.equals("java.net.UnknownHostException")
                || cls.equals("java.io.IOException")) {
            return true;
        }
        // Spring JDBC — DB connectivity / lock / deadlock / timeout
        if (cls.equals("org.springframework.jdbc.CannotGetJdbcConnectionException")
                || cls.equals("org.springframework.jdbc.CannotAcquireLockException")
                || cls.equals("org.springframework.dao.CannotAcquireLockException")
                || cls.equals("org.springframework.dao.DeadlockLoserDataAccessException")
                || cls.equals("org.springframework.dao.QueryTimeoutException")
                || cls.equals("org.springframework.dao.DataAccessResourceFailureException")
                || cls.equals("org.springframework.dao.CannotCreateTransactionException")
                || cls.equals("org.springframework.dao.RecoverableDataAccessException")) {
            return true;
        }
        // HikariCP — connection pool failures
        if (cls.equals("com.zaxxer.hikari.pool.HikariPool$PoolInitializationException")
                || cls.equals("com.zaxxer.hikari.pool.HikariPool$CannotAcquireConnectionException")) {
            return true;
        }
        // PostgreSQL JDBC — connection failures
        if (cls.equals("org.postgresql.util.PSQLException")
                && isPostgresTransient(t.getMessage())) {
            return true;
        }

        return false;
    }

    /**
     * Heuristic for PostgreSQL-specific transient conditions detectable in the error message.
     * Covers deadlock, admin cancel, timeout, and connection-refused.
     */
    private boolean isPostgresTransient(String message) {
        if (message == null) {
            return false;
        }
        // PostgreSQL error codes: 40P01 = deadlock, 57014 = query-cancel, 08006 = connection-failure
        return message.contains("40P01")
                || message.contains("57014")
                || message.contains("08006")
                || message.contains("deadlock")
                || message.contains("cancel");
    }
}
