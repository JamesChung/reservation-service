package org.example.reservation;

import java.time.Instant;
import java.util.Objects;

/**
 * A granted hold. Snapshot of one generation; not a handle.
 * {@code release}/{@code extend} must pass this object so the {@link LeaseToken} can fence
 * a reused {@link ReservationId}.
 */
public record Reservation(
        ReservationId id,
        LeaseToken token,
        Owner owner,
        Scope scope,
        ResourceVector resources,
        Instant createdAt,
        Instant expiresAt
) {

    public Reservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
