package org.example.reservation;

/**
 * {@link ReservationId} is already held with a different scope or resource vector.
 */
public final class ReservationConflict extends RuntimeException {

    private final Reservation existing;

    public ReservationConflict(Reservation existing) {
        super("reservation " + existing.id().value() + " already held with a different scope or vector");
        this.existing = existing;
    }

    public Reservation existing() {
        return existing;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
