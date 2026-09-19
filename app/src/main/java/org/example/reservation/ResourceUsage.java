package org.example.reservation;

/**
 * Occupancy versus configured limit for one resource in a scope.
 *
 * {@code limit} is {@code 0} when the name is in use (leftover holds) but no longer in the quota.
 */
public record ResourceUsage(long used, long limit) {

    public ResourceUsage {
        if (used < 0 || limit < 0) {
            throw new IllegalArgumentException("used and limit must be non-negative");
        }
    }

    public long available() {
        return Math.max(0, limit - used);
    }
}
