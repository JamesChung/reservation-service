package org.example.reservation;

/**
 * Store or infrastructure failure. Admission denials are {@link Denied} on a {@link Try} failure.
 * Lookups miss with {@link NotFound}; stale heartbeats with {@link StaleLease}.
 */
public class ReservationException extends RuntimeException {

    public ReservationException(String message) {
        super(message);
    }

    public ReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
