package org.example.reservation;

import java.util.Objects;

/**
 * One dimension that prevented a grant (or that would, for a waitable denial).
 *
 * @param requested amount asked in this reservation
 * @param used      occupancy of this name in the scope at decision time
 * @param limit     configured quota for the name, or {@code 0} if unconfigured
 */
public record ResourceShortage(ResourceName name, long requested, long used, long limit) {

    public ResourceShortage {
        Objects.requireNonNull(name, "name");
        if (requested <= 0) {
            throw new IllegalArgumentException("requested must be positive");
        }
        if (used < 0 || limit < 0) {
            throw new IllegalArgumentException("used and limit must be non-negative");
        }
    }
}
