package org.example.reservation;

import java.util.Objects;

/**
 * {@link ReservationRetries} waited the full timeout and capacity never became available.
 * The store never produces this.
 */
public final class TimedOut extends RuntimeException {

    private final UsageSnapshot usage;

    public TimedOut(UsageSnapshot usage) {
        super("timed out");
        this.usage = Objects.requireNonNull(usage, "usage");
    }

    public UsageSnapshot usage() {
        return usage;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
