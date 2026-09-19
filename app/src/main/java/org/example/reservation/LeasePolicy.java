package org.example.reservation;

import java.time.Duration;
import java.util.Objects;

/** Bounds for lease TTLs and the default retry poll interval. */
public final class LeasePolicy {

    public static final Duration MIN_TTL = Duration.ofSeconds(1);
    public static final Duration MAX_TTL = Duration.ofHours(24);
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);

    private LeasePolicy() {}

    public static Duration requireTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(
                    "ttl must be between " + MIN_TTL + " and " + MAX_TTL + " (inclusive), got " + ttl);
        }
        return ttl;
    }

    public static Duration requirePollInterval(Duration pollInterval) {
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("poll interval must be positive");
        }
        if (pollInterval.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("poll interval must be at most " + MAX_TTL);
        }
        return pollInterval;
    }
}
