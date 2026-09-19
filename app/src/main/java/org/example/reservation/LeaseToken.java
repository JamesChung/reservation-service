package org.example.reservation;

import java.util.Objects;

/**
 * Unguessable token for one grant generation. {@code release}/{@code extend} must present
 * the token from the grant they observed; a reused {@link ReservationId} gets a new token.
 */
public record LeaseToken(String value) {

    public LeaseToken {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("lease token must be non-blank");
        }
    }
}
