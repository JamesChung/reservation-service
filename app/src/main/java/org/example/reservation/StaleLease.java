package org.example.reservation;

/**
 * {@code extend} saw an expired hold or a token from another generation.
 * The caller must stop work and stop heartbeating.
 */
public final class StaleLease extends RuntimeException {

    private final ReservationId id;

    public StaleLease(ReservationId id) {
        super("stale lease: " + id.value());
        this.id = id;
    }

    public ReservationId id() {
        return id;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
