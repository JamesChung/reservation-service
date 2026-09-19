package org.example.reservation;

import java.util.Objects;

/**
 * Caller-supplied identity of a reservation. Doubles as the idempotency key:
 * a second grant with the same id and the same scope/vector returns the existing hold.
 */
public record ReservationId(String value) {

    public ReservationId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("reservation id must be non-blank");
        }
    }
}
