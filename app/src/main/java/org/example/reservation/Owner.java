package org.example.reservation;

import java.util.Objects;

/**
 * Actor that first obtained a hold (scheduler pod, worker id, …).
 * Not part of the idempotency key; set once at first grant and never updated.
 */
public record Owner(String value) {

    public Owner {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("owner must be non-blank");
        }
    }
}
