package org.example.reservation;

/**
 * No row for a lookup ({@code find}, {@code quotas}).
 * Carried as {@link Try.Failure#cause()}.
 */
public final class NotFound extends RuntimeException {

    public NotFound(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
