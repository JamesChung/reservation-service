package org.example.reservation;

import java.time.Duration;
import java.util.Objects;

/**
 * Demand to hold a resource vector until release or lease expiry.
 *
 * {@link #id()} is the idempotency key. {@link #owner()} is recorded on first grant only.
 * {@link #ttl()} must be in {@link LeasePolicy#MIN_TTL}..{@link LeasePolicy#MAX_TTL}.
 */
public record ReserveRequest(
        ReservationId id,
        Owner owner,
        Scope scope,
        ResourceVector resources,
        Duration ttl
) {

    public ReserveRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resources, "resources");
        LeasePolicy.requireTtl(ttl);
    }
}
