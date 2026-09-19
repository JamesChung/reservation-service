package org.example.reservation;

import java.util.Objects;

/**
 * Isolation boundary for quotas and occupancy, e.g. a tenant.
 *
 * {@code type} is a caller-defined vocabulary ({@code "tenant"}, {@code "project"}, …).
 * The reservation library does not interpret it.
 */
public record Scope(String type, String id) {

    public Scope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("scope type and id must be non-blank");
        }
    }

    public static Scope tenant(String tenantId) {
        return new Scope("tenant", tenantId);
    }
}
